package com.orthoflow.recall.infrastructure;

import com.orthoflow.recall.application.dto.RecallDtos.Filter;
import com.orthoflow.recall.application.dto.RecallDtos.Kind;
import com.orthoflow.recall.application.dto.RecallDtos.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Recall lists are queries over what the clinic already knows — no new data. One
 * base statement carries each patient's last completed visit, next booked visit
 * and treatment progress; each list is a predicate over those columns.
 */
@Component
@RequiredArgsConstructor
public class RecallQuery {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String BASE = """
            WITH base AS (
              SELECT p.id, p.patient_code, p.first_name, p.last_name, p.phone, p.email,
                     p.primary_practitioner_id, pr.display_name AS practitioner_name,
                     (SELECT max(a.date_time) FROM appointments a WHERE a.patient_id = p.id AND a.status = 'COMPLETED') AS last_visit,
                     (SELECT min(a.date_time) FROM appointments a WHERE a.patient_id = p.id AND a.date_time >= :now
                        AND a.status IN ('SCHEDULED', 'CONFIRMED', 'LATE')) AS next_appointment,
                     COALESCE((SELECT CAST(round(avg(t.progress)) AS INT) FROM patient_treatments t
                               WHERE t.patient_id = p.id AND t.deleted_at IS NULL AND t.status IN ('PLANNED', 'ACTIVE')), 0) AS progress,
                     EXISTS (SELECT 1 FROM patient_treatments t WHERE t.patient_id = p.id AND t.deleted_at IS NULL
                             AND t.status = 'ACTIVE') AS has_active_treatment,
                     (SELECT max(a.date_time) FROM appointments a JOIN appointment_types t ON t.id = a.appointment_type_id
                       WHERE a.patient_id = p.id AND a.status = 'COMPLETED' AND t.code = 'DEBOND') AS debonded_at
              FROM patients p LEFT JOIN practitioners pr ON pr.id = p.primary_practitioner_id
              WHERE p.practice_id = :practice AND p.deleted_at IS NULL AND p.status <> 'INACTIVE'
                AND (CAST(:practitioner AS UUID) IS NULL OR p.primary_practitioner_id = CAST(:practitioner AS UUID))
            )
            SELECT * FROM base b WHERE 1 = 1
            """;

    public List<Row> run(Filter f, OffsetDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("practice", f.practiceId())
                .addValue("practitioner", f.practitionerId())
                .addValue("now", now);
        StringBuilder sql = new StringBuilder(BASE);
        Kind kind = f.kind();
        switch (kind) {
            case NO_VISIT_1M, NO_VISIT_3M, NO_VISIT_6M -> {
                sql.append(" AND b.next_appointment IS NULL AND (b.last_visit < :cutoff")
                        .append(f.excludeNeverVisited() ? ")" : " OR b.last_visit IS NULL)")
                        .append(f.excludeNeverVisited() ? " AND b.last_visit IS NOT NULL" : "");
                params.addValue("cutoff", now.minusMonths(kind.months()));
            }
            case NOTHING_SCHEDULED_1M, NOTHING_SCHEDULED_12M -> {
                sql.append(" AND NOT EXISTS (SELECT 1 FROM appointments a WHERE a.patient_id = b.id AND a.date_time >= :now")
                        .append(" AND a.date_time < :horizon AND a.status IN ('SCHEDULED', 'CONFIRMED', 'LATE'))");
                if (f.excludeNeverVisited()) {
                    sql.append(" AND b.last_visit IS NOT NULL");
                }
                params.addValue("horizon", now.plusMonths(kind.months()));
            }
            case LOST_TO_FOLLOW_UP ->
                    sql.append(" AND b.has_active_treatment AND b.next_appointment IS NULL");
            case RETENTION_DUE_6M, RETENTION_DUE_12M -> {
                sql.append(" AND b.debonded_at IS NOT NULL AND b.debonded_at + (CAST(:months AS INT) * INTERVAL '1 month') <= :now")
                        .append(" AND b.next_appointment IS NULL")
                        .append(" AND NOT EXISTS (SELECT 1 FROM appointments a JOIN appointment_types t ON t.id = a.appointment_type_id")
                        .append(" WHERE a.patient_id = b.id AND t.code = 'RETENTION_CHECK' AND a.status NOT IN ('CANCELLED', 'NO_SHOW')")
                        .append(" AND a.date_time >= b.debonded_at + (CAST(:months AS INT) * INTERVAL '1 month') - INTERVAL '30 days')");
                params.addValue("months", kind.months());
            }
        }
        if (f.minProgress() != null) {
            sql.append(" AND b.progress >= :minProgress");
            params.addValue("minProgress", f.minProgress());
        }
        if (f.maxProgress() != null) {
            sql.append(" AND b.progress <= :maxProgress");
            params.addValue("maxProgress", f.maxProgress());
        }
        sql.append(switch (f.sort() == null ? "" : f.sort()) {
            case "remaining" -> " ORDER BY (100 - b.progress) DESC, b.last_visit ASC NULLS FIRST";
            case "name" -> " ORDER BY lower(b.last_name), lower(b.first_name)";
            default -> " ORDER BY b.last_visit ASC NULLS FIRST, lower(b.last_name)";
        }).append(" LIMIT 1000");

        return jdbc.query(sql.toString(), params, (rs, i) -> {
            OffsetDateTime debonded = rs.getObject("debonded_at", OffsetDateTime.class);
            int progress = rs.getInt("progress");
            LocalDate dueSince = debonded == null || kind.months() == 0 || !kind.name().startsWith("RETENTION") ? null
                    : debonded.plusMonths(kind.months()).toLocalDate();
            return new Row(rs.getObject("id", UUID.class), rs.getString("patient_code"), rs.getString("first_name"),
                    rs.getString("last_name"), rs.getString("phone"), rs.getString("email"),
                    rs.getObject("primary_practitioner_id", UUID.class), rs.getString("practitioner_name"),
                    rs.getObject("last_visit", OffsetDateTime.class), rs.getObject("next_appointment", OffsetDateTime.class),
                    progress, Math.max(0, 100 - progress), dueSince, null);
        });
    }
}
