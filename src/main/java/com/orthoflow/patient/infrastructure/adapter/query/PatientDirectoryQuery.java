package com.orthoflow.patient.infrastructure.adapter.query;

import com.orthoflow.patient.application.dto.PatientDirectoryDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The patient list as a read model: one SQL statement that joins in what a
 * receptionist scans for (insurer, usual doctor, treatment progress, next and
 * last visit) instead of an entity per row and a query per column. Sorting is
 * through a whitelist, never a column name from the request, so it cannot be
 * used to inject SQL.
 */
@Component
@RequiredArgsConstructor
public class PatientDirectoryQuery {

    /** Pairs of probable duplicates: same CIN; same phone with a similar name; or a near-identical name and birth date. */
    private static final String DUPLICATE_PAIRS = """
            SELECT a.id AS a_id, b.id AS b_id, 'CIN' AS reason, 1.0 AS score
            FROM patients a JOIN patients b ON a.id < b.id AND a.practice_id = b.practice_id AND lower(a.cin) = lower(b.cin)
            WHERE a.practice_id = :practice AND a.deleted_at IS NULL AND b.deleted_at IS NULL
              AND a.cin IS NOT NULL AND btrim(a.cin) <> ''
            UNION ALL
            SELECT a.id, b.id, 'PHONE',
                   similarity(lower(a.first_name || ' ' || a.last_name), lower(b.first_name || ' ' || b.last_name))
            FROM patients a JOIN patients b ON a.id < b.id AND a.practice_id = b.practice_id
                 AND phone_digits(a.phone) = phone_digits(b.phone)
            WHERE a.practice_id = :practice AND a.deleted_at IS NULL AND b.deleted_at IS NULL
              AND phone_digits(a.phone) IS NOT NULL
              AND similarity(lower(a.first_name || ' ' || a.last_name), lower(b.first_name || ' ' || b.last_name)) >= 0.35
            UNION ALL
            SELECT a.id, b.id, 'NAME',
                   similarity(lower(a.first_name || ' ' || a.last_name), lower(b.first_name || ' ' || b.last_name))
            FROM patients a JOIN patients b ON a.id < b.id AND a.practice_id = b.practice_id
                 AND lower(a.first_name || ' ' || a.last_name) % lower(b.first_name || ' ' || b.last_name)
            WHERE a.practice_id = :practice AND a.deleted_at IS NULL AND b.deleted_at IS NULL
              AND similarity(lower(a.first_name || ' ' || a.last_name), lower(b.first_name || ' ' || b.last_name)) >= 0.7
              AND (a.date_of_birth IS NULL OR b.date_of_birth IS NULL OR a.date_of_birth = b.date_of_birth)
            """;

    /** Each sort is a list of keys, because the direction applies to every one of them, not just the last. */
    private static final Map<String, List<String>> SORTS = Map.of(
            "name", List.of("lower(p.last_name)", "lower(p.first_name)"),
            "code", List.of("p.patient_code"),
            "created", List.of("p.created_at"),
            "age", List.of("p.date_of_birth"),
            "progress", List.of("progress"),
            "balance", List.of("balance_due"),
            "next", List.of("next_appointment"),
            "last", List.of("last_visit"));

    private final NamedParameterJdbcTemplate jdbc;

    public record Filter(UUID practiceId, String search, String gender, String status, UUID practitionerId,
                         UUID insurerId, boolean duplicatesOnly, boolean debtOnly) {

        public Filter(UUID practiceId, String search, String gender, String status, UUID practitionerId,
                      UUID insurerId, boolean duplicatesOnly) {
            this(practiceId, search, gender, status, practitionerId, insurerId, duplicatesOnly, false);
        }
    }

    public List<Row> page(Filter f, String sort, boolean descending, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(f, params);
        String direction = descending ? " DESC" : " ASC";
        String order = SORTS.getOrDefault(sort, SORTS.get("name")).stream()
                .map(key -> key + direction + " NULLS LAST").collect(java.util.stream.Collectors.joining(", "));
        params.addValue("limit", size).addValue("offset", (long) page * size);
        String sql = """
                SELECT p.id, p.patient_code, p.first_name, p.last_name, p.gender, p.date_of_birth,
                       CASE WHEN p.date_of_birth IS NULL THEN NULL
                            ELSE CAST(EXTRACT(YEAR FROM age(p.date_of_birth)) AS INT) END AS age,
                       p.phone, p.email, p.status,
                       COALESCE(i.name, p.insurance_provider) AS insurer_name,
                       p.primary_practitioner_id, pr.display_name AS practitioner_name,
                       COALESCE((SELECT CAST(round(avg(t.progress)) AS INT) FROM patient_treatments t
                                 WHERE t.patient_id = p.id AND t.deleted_at IS NULL AND t.status <> 'CANCELLED'), 0) AS progress,
                       (SELECT min(a.date_time) FROM appointments a WHERE a.patient_id = p.id AND a.date_time >= now()
                          AND a.status IN ('SCHEDULED', 'CONFIRMED', 'LATE')) AS next_appointment,
                       (SELECT max(a.date_time) FROM appointments a WHERE a.patient_id = p.id AND a.status = 'COMPLETED') AS last_visit,
                       p.photo_file_id, p.created_at,
                       (SELECT COALESCE(sum(i.total - COALESCE(x.paid, 0)), 0) FROM invoices i
                          LEFT JOIN (SELECT invoice_id, sum(amount) AS paid FROM payments GROUP BY invoice_id) x ON x.invoice_id = i.id
                         WHERE i.patient_id = p.id AND i.status <> 'CANCELLED') AS balance_due,
                       (SELECT COALESCE(sum(r.amount - COALESCE(a.allocated, 0)), 0) FROM receipts r
                          LEFT JOIN (SELECT receipt_id, sum(amount) AS allocated FROM payments GROUP BY receipt_id) a ON a.receipt_id = r.id
                         WHERE r.patient_id = p.id AND r.voided_at IS NULL) AS credit
                FROM patients p
                LEFT JOIN insurers i ON i.id = p.insurer_id
                LEFT JOIN practitioners pr ON pr.id = p.primary_practitioner_id
                """ + where + " ORDER BY " + order + ", p.id LIMIT :limit OFFSET :offset";
        return jdbc.query(withPairs(f, sql), params, PatientDirectoryQuery::row);
    }

    public long count(Filter f) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "SELECT count(*) FROM patients p " + where(f, params);
        Long n = jdbc.queryForObject(withPairs(f, sql), params, Long.class);
        return n == null ? 0 : n;
    }

    public Kpis kpis(UUID practiceId, OffsetDateTime monthStart) {
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE created_at >= :monthStart) AS new_this_month,
                       count(*) FILTER (WHERE gender = 'M') AS male,
                       count(*) FILTER (WHERE gender = 'F') AS female,
                       count(*) FILTER (WHERE gender IS NULL OR gender NOT IN ('M', 'F')) AS other,
                       avg(EXTRACT(YEAR FROM age(date_of_birth))) FILTER (WHERE date_of_birth IS NOT NULL) AS avg_age
                FROM patients WHERE practice_id = :practice AND deleted_at IS NULL
                """, new MapSqlParameterSource("practice", practiceId).addValue("monthStart", monthStart),
                (rs, i) -> new Kpis(rs.getLong("total"), rs.getLong("new_this_month"), rs.getLong("male"),
                        rs.getLong("female"), rs.getLong("other"),
                        rs.getObject("avg_age") == null ? null : Math.round(rs.getDouble("avg_age") * 10) / 10.0));
    }

    public List<DuplicatePair> duplicates(UUID practiceId, int limit) {
        String sql = """
                WITH pairs AS (""" + DUPLICATE_PAIRS + """
                )
                SELECT pa.reason, pa.score,
                       a.id AS a_id, a.patient_code AS a_code, a.first_name AS a_first, a.last_name AS a_last, a.date_of_birth AS a_dob,
                       a.phone AS a_phone, a.cin AS a_cin, a.email AS a_email, a.created_at AS a_created,
                       b.id AS b_id, b.patient_code AS b_code, b.first_name AS b_first, b.last_name AS b_last, b.date_of_birth AS b_dob,
                       b.phone AS b_phone, b.cin AS b_cin, b.email AS b_email, b.created_at AS b_created
                FROM pairs pa JOIN patients a ON a.id = pa.a_id JOIN patients b ON b.id = pa.b_id
                ORDER BY pa.score DESC, a.last_name
                LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource("practice", practiceId).addValue("limit", limit),
                (rs, i) -> new DuplicatePair(person(rs, "a_"), person(rs, "b_"), rs.getString("reason"), rs.getDouble("score")));
    }

    private static String where(Filter f, MapSqlParameterSource params) {
        StringBuilder sb = new StringBuilder(" WHERE p.deleted_at IS NULL AND p.practice_id = :practice");
        params.addValue("practice", f.practiceId());
        if (f.search() != null && !f.search().isBlank()) {
            sb.append("""
                     AND (lower(p.first_name || ' ' || p.last_name) LIKE :like ESCAPE '\\'
                          OR lower(p.last_name || ' ' || p.first_name) LIKE :like ESCAPE '\\'
                          OR lower(p.patient_code) LIKE :like ESCAPE '\\' OR lower(coalesce(p.cin, '')) LIKE :like ESCAPE '\\'
                          OR lower(coalesce(p.email, '')) LIKE :like ESCAPE '\\' OR coalesce(p.phone, '') LIKE :like ESCAPE '\\'
                          OR phone_digits(p.phone) = :digits)
                    """);
            // The user's % and _ are literal characters, not wildcards.
            params.addValue("like", "%" + f.search().trim().toLowerCase()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            String digits = f.search().replaceAll("[^0-9]", "");
            params.addValue("digits", digits.length() >= 6 ? digits.substring(Math.max(0, digits.length() - 9)) : "");
        }
        if (f.gender() != null) {
            sb.append(" AND p.gender = :gender");
            params.addValue("gender", f.gender());
        }
        if (f.status() != null) {
            sb.append(" AND p.status = :status");
            params.addValue("status", f.status());
        }
        if (f.practitionerId() != null) {
            sb.append(" AND p.primary_practitioner_id = :practitioner");
            params.addValue("practitioner", f.practitionerId());
        }
        if (f.insurerId() != null) {
            sb.append(" AND p.insurer_id = :insurer");
            params.addValue("insurer", f.insurerId());
        }
        if (f.debtOnly()) {
            sb.append(" AND (SELECT COALESCE(sum(i.total - COALESCE(x.paid, 0)), 0) FROM invoices i"
                    + " LEFT JOIN (SELECT invoice_id, sum(amount) AS paid FROM payments GROUP BY invoice_id) x ON x.invoice_id = i.id"
                    + " WHERE i.patient_id = p.id AND i.status <> 'CANCELLED') > 0");
        }
        if (f.duplicatesOnly()) {
            sb.append(" AND p.id IN (SELECT a_id FROM pairs UNION SELECT b_id FROM pairs)");
        }
        return sb.toString();
    }

    private static String withPairs(Filter f, String sql) {
        return f.duplicatesOnly() ? "WITH pairs AS (" + DUPLICATE_PAIRS + ") " + sql : sql;
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("patient_code"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("gender"),
                rs.getObject("date_of_birth", java.time.LocalDate.class),
                rs.getObject("age") == null ? null : rs.getInt("age"), rs.getString("phone"), rs.getString("email"),
                rs.getString("status"), rs.getString("insurer_name"), rs.getObject("primary_practitioner_id", UUID.class),
                rs.getString("practitioner_name"), rs.getInt("progress"),
                rs.getObject("next_appointment", OffsetDateTime.class), rs.getObject("last_visit", OffsetDateTime.class),
                rs.getObject("photo_file_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getBigDecimal("balance_due"), rs.getBigDecimal("credit"));
    }

    private static Person person(ResultSet rs, String p) throws SQLException {
        return new Person(rs.getObject(p + "id", UUID.class), rs.getString(p + "code"), rs.getString(p + "first"),
                rs.getString(p + "last"), rs.getObject(p + "dob", java.time.LocalDate.class), rs.getString(p + "phone"),
                rs.getString(p + "cin"), rs.getString(p + "email"), rs.getObject(p + "created", OffsetDateTime.class));
    }
}
