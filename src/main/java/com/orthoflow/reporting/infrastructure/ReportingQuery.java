package com.orthoflow.reporting.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The aggregations behind the analytics screens. Plain SQL: each is one pass over
 * a table that an entity graph would load a row at a time.
 */
@Component
@RequiredArgsConstructor
public class ReportingQuery {

    private final NamedParameterJdbcTemplate jdbc;

    private static MapSqlParameterSource p(UUID practice, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("practice", practice).addValue("from", from).addValue("to", to);
    }

    // ── Procedure activity ──
    public record ProcedureAgg(UUID treatmentId, String name, String category, UUID practitionerId, String practitionerName,
                               String status, long sessions, BigDecimal revenue, BigDecimal material) {
    }

    public List<ProcedureAgg> procedures(UUID practice, LocalDate from, LocalDate to, Collection<String> statuses, UUID practitionerId,
                                         String category) {
        MapSqlParameterSource params = p(practice, from, to).addValue("statuses", statuses);
        StringBuilder sql = new StringBuilder("""
                SELECT t.id AS treatment_id, t.name, COALESCE(t.category, '') AS category, i.practitioner_id, pr.display_name,
                       ti.status, count(*) AS sessions, sum(ti.treatment_price) AS revenue, sum(ti.consumables_cost) AS material
                FROM treatment_invoices ti
                JOIN treatments t ON t.id = ti.treatment_id
                LEFT JOIN invoices i ON i.id = ti.billing_invoice_id
                LEFT JOIN practitioners pr ON pr.id = i.practitioner_id
                WHERE ti.practice_id = :practice AND ti.session_date BETWEEN :from AND :to AND ti.status IN (:statuses)
                """);
        if (practitionerId != null) {
            sql.append(" AND i.practitioner_id = :practitioner");
            params.addValue("practitioner", practitionerId);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND COALESCE(t.category, '') = :category");
            params.addValue("category", category);
        }
        sql.append(" GROUP BY t.id, t.name, t.category, i.practitioner_id, pr.display_name, ti.status ORDER BY sum(ti.treatment_price) DESC, t.name");
        return jdbc.query(sql.toString(), params, (rs, n) -> new ProcedureAgg(rs.getObject("treatment_id", UUID.class), rs.getString("name"),
                rs.getString("category"), rs.getObject("practitioner_id", UUID.class), rs.getString("display_name"), rs.getString("status"),
                rs.getLong("sessions"), rs.getBigDecimal("revenue"), rs.getBigDecimal("material")));
    }

    // ── Doctor time ──
    public record VisitRow(UUID id, OffsetDateTime dateTime, UUID practitionerId, String practitionerName, String typeName,
                           int plannedMinutes, OffsetDateTime arrivedAt, OffsetDateTime seatedAt, OffsetDateTime finishedAt,
                           String patientCode, String status) {
    }

    public List<VisitRow> visits(UUID practice, OffsetDateTime start, OffsetDateTime end, UUID practitionerId, String lang) {
        MapSqlParameterSource params = new MapSqlParameterSource("practice", practice).addValue("start", start).addValue("end", end)
                .addValue("lang", lang);
        String filter = "";
        if (practitionerId != null) {
            filter = " AND a.practitioner_id = :practitioner";
            params.addValue("practitioner", practitionerId);
        }
        return jdbc.query("""
                SELECT a.id, a.date_time, a.practitioner_id, pr.display_name,
                       COALESCE(CASE :lang WHEN 'en' THEN at.name_en WHEN 'ar' THEN at.name_ar ELSE at.name_fr END, a.type) AS type_name,
                       a.duration_minutes, a.arrived_at, a.seated_at, a.finished_at, pa.patient_code, a.status
                FROM appointments a
                LEFT JOIN practitioners pr ON pr.id = a.practitioner_id
                LEFT JOIN appointment_types at ON at.id = a.appointment_type_id
                LEFT JOIN patients pa ON pa.id = a.patient_id
                WHERE a.practice_id = :practice AND a.date_time >= :start AND a.date_time < :end
                  AND a.status NOT IN ('CANCELLED', 'NO_SHOW')""" + filter, params,
                (rs, n) -> new VisitRow(rs.getObject("id", UUID.class), rs.getObject("date_time", OffsetDateTime.class),
                        rs.getObject("practitioner_id", UUID.class), rs.getString("display_name"), rs.getString("type_name"),
                        rs.getInt("duration_minutes"), rs.getObject("arrived_at", OffsetDateTime.class),
                        rs.getObject("seated_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
                        rs.getString("patient_code"), rs.getString("status")));
    }

    // ── Income statement ──
    public record DayAmount(LocalDate day, BigDecimal amount) {
    }

    public record ExpenseDay(LocalDate day, String code, String kind, String name, int order, BigDecimal amount) {
    }

    public List<DayAmount> collectedByDay(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT receipt_date AS d, sum(amount) AS v FROM receipts WHERE practice_id = :practice AND voided_at IS NULL AND receipt_date BETWEEN :from AND :to GROUP BY receipt_date",
                p(practice, from, to), (rs, n) -> new DayAmount(rs.getObject("d", LocalDate.class), rs.getBigDecimal("v")));
    }

    public List<DayAmount> producedByDay(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT issue_date AS d, sum(total) AS v FROM invoices WHERE practice_id = :practice AND status <> 'CANCELLED' AND issue_date BETWEEN :from AND :to GROUP BY issue_date",
                p(practice, from, to), (rs, n) -> new DayAmount(rs.getObject("d", LocalDate.class), rs.getBigDecimal("v")));
    }

    public List<ExpenseDay> expensesByDay(UUID practice, LocalDate from, LocalDate to, String lang) {
        return jdbc.query("""
                SELECT e.expense_date AS d, c.code, c.kind,
                       CASE :lang WHEN 'en' THEN c.name_en WHEN 'ar' THEN c.name_ar ELSE c.name_fr END AS name,
                       c.display_order, sum(e.amount) AS v
                FROM expenses e JOIN expense_categories c ON c.id = e.category_id
                WHERE e.practice_id = :practice AND e.status <> 'CANCELLED' AND e.expense_date BETWEEN :from AND :to
                GROUP BY e.expense_date, c.code, c.kind, c.name_fr, c.name_en, c.name_ar, c.display_order""",
                p(practice, from, to).addValue("lang", lang),
                (rs, n) -> new ExpenseDay(rs.getObject("d", LocalDate.class), rs.getString("code"), rs.getString("kind"), rs.getString("name"),
                        rs.getInt("display_order"), rs.getBigDecimal("v")));
    }

    // ── Goals ──
    public record MonthAmount(int month, BigDecimal amount) {
    }

    public List<MonthAmount> collectedByMonth(UUID practice, int year) {
        return jdbc.query("SELECT extract(month FROM receipt_date)::int AS m, sum(amount) AS v FROM receipts WHERE practice_id = :practice AND voided_at IS NULL AND extract(year FROM receipt_date) = :year GROUP BY 1",
                new MapSqlParameterSource("practice", practice).addValue("year", year),
                (rs, n) -> new MonthAmount(rs.getInt("m"), rs.getBigDecimal("v")));
    }

    public List<MonthAmount> producedByMonth(UUID practice, int year) {
        return jdbc.query("SELECT extract(month FROM issue_date)::int AS m, sum(total) AS v FROM invoices WHERE practice_id = :practice AND status <> 'CANCELLED' AND extract(year FROM issue_date) = :year GROUP BY 1",
                new MapSqlParameterSource("practice", practice).addValue("year", year),
                (rs, n) -> new MonthAmount(rs.getInt("m"), rs.getBigDecimal("v")));
    }

    /** Spend per expense-category kind over a period, for the wizard's suggested fixed and variable costs. */
    public List<Breakdown> expensesByKind(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT c.kind AS k, sum(e.amount) AS v FROM expenses e JOIN expense_categories c ON c.id = e.category_id WHERE e.practice_id = :practice AND e.status <> 'CANCELLED' AND e.expense_date BETWEEN :from AND :to GROUP BY c.kind",
                p(practice, from, to), (rs, n) -> new Breakdown(rs.getString("k"), rs.getBigDecimal("v")));
    }

    public BigDecimal collected(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT COALESCE(sum(amount), 0) FROM receipts WHERE practice_id = :practice AND voided_at IS NULL AND receipt_date BETWEEN :from AND :to",
                p(practice, from, to), BigDecimal.class);
    }

    public record Breakdown(String key, BigDecimal amount) {
    }
}
