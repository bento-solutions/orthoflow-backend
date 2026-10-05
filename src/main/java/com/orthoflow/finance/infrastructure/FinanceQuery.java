package com.orthoflow.finance.infrastructure;

import com.orthoflow.billing.domain.model.PaymentMethod;
import com.orthoflow.finance.application.dto.FinanceDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The money read models: collections, debts, dashboard figures. Plain SQL, because
 * these are aggregations across invoices, receipts and expenses that an entity
 * graph would load row by row. Cancelled invoices and voided receipts never count.
 */
@Component
@RequiredArgsConstructor
public class FinanceQuery {

    private final NamedParameterJdbcTemplate jdbc;

    private static MapSqlParameterSource p(UUID practice, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("practice", practice).addValue("from", from).addValue("to", to);
    }

    // ── Collections ──
    public List<CollectionRow> receipts(UUID practice, LocalDate from, LocalDate to, PaymentMethod method, UUID practitioner) {
        MapSqlParameterSource params = p(practice, from, to);
        StringBuilder sql = new StringBuilder("""
                SELECT r.id, r.receipt_date, r.amount, r.method, r.reference, r.patient_id, pa.first_name, pa.last_name, pa.patient_code,
                       r.practitioner_id, pr.display_name,
                       r.amount - COALESCE((SELECT sum(x.amount) FROM payments x WHERE x.receipt_id = r.id), 0) AS unallocated
                FROM receipts r JOIN patients pa ON pa.id = r.patient_id LEFT JOIN practitioners pr ON pr.id = r.practitioner_id
                WHERE r.practice_id = :practice AND r.voided_at IS NULL AND r.receipt_date BETWEEN :from AND :to
                """);
        if (method != null) {
            sql.append(" AND r.method = :method");
            params.addValue("method", method.name());
        }
        if (practitioner != null) {
            sql.append(" AND r.practitioner_id = :practitioner");
            params.addValue("practitioner", practitioner);
        }
        sql.append(" ORDER BY r.receipt_date DESC, r.created_at DESC");
        return jdbc.query(sql.toString(), params, (rs, i) -> new CollectionRow(rs.getObject("id", UUID.class),
                rs.getObject("receipt_date", LocalDate.class), rs.getBigDecimal("amount"), PaymentMethod.valueOf(rs.getString("method")),
                rs.getString("reference"), rs.getObject("patient_id", UUID.class),
                rs.getString("first_name") + " " + rs.getString("last_name"), rs.getString("patient_code"),
                rs.getObject("practitioner_id", UUID.class), rs.getString("display_name"), rs.getBigDecimal("unallocated")));
    }

    public BigDecimal allocated(UUID practice, LocalDate from, LocalDate to, boolean advancesOnly) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(pay.amount), 0)
                FROM payments pay JOIN invoices i ON i.id = pay.invoice_id JOIN receipts r ON r.id = pay.receipt_id
                WHERE i.practice_id = :practice AND r.voided_at IS NULL AND pay.payment_date BETWEEN :from AND :to
                """ + (advancesOnly ? " AND r.receipt_date < pay.payment_date" : ""), p(practice, from, to), BigDecimal.class);
    }

    public BigDecimal creditAvailable(UUID practice) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(r.amount - COALESCE(a.allocated, 0)), 0)
                FROM receipts r LEFT JOIN (SELECT receipt_id, sum(amount) AS allocated FROM payments GROUP BY receipt_id) a ON a.receipt_id = r.id
                WHERE r.practice_id = :practice AND r.voided_at IS NULL
                """, new MapSqlParameterSource("practice", practice), BigDecimal.class);
    }

    // ── Debts ──
    public List<DebtRow> debts(UUID practice, LocalDate from, LocalDate to, boolean debtOnly, String search, String sort) {
        MapSqlParameterSource params = new MapSqlParameterSource("practice", practice);
        String period = "";
        if (from != null && to != null) {
            period = " AND i.issue_date BETWEEN :from AND :to";
            params.addValue("from", from).addValue("to", to);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM (
                  SELECT pa.id, pa.patient_code, pa.first_name, pa.last_name, pa.phone,
                         COALESCE(f.fees, 0) AS fees, COALESCE(f.paid, 0) AS paid, COALESCE(f.fees, 0) - COALESCE(f.paid, 0) AS balance,
                         COALESCE(c.credit, 0) AS credit, f.last_invoice, pm.last_payment
                  FROM patients pa
                  LEFT JOIN (SELECT i.patient_id, sum(i.total) AS fees, sum(COALESCE(x.paid, 0)) AS paid, max(i.issue_date) AS last_invoice
                             FROM invoices i LEFT JOIN (SELECT invoice_id, sum(amount) AS paid FROM payments GROUP BY invoice_id) x ON x.invoice_id = i.id
                             WHERE i.practice_id = :practice AND i.status <> 'CANCELLED'""" + period + """
                
                             GROUP BY i.patient_id) f ON f.patient_id = pa.id
                  LEFT JOIN (SELECT r.patient_id, sum(r.amount - COALESCE(a.allocated, 0)) AS credit
                             FROM receipts r LEFT JOIN (SELECT receipt_id, sum(amount) AS allocated FROM payments GROUP BY receipt_id) a ON a.receipt_id = r.id
                             WHERE r.practice_id = :practice AND r.voided_at IS NULL GROUP BY r.patient_id) c ON c.patient_id = pa.id
                  LEFT JOIN (SELECT patient_id, max(receipt_date) AS last_payment FROM receipts WHERE voided_at IS NULL GROUP BY patient_id) pm
                         ON pm.patient_id = pa.id
                  WHERE pa.practice_id = :practice AND pa.deleted_at IS NULL AND (f.patient_id IS NOT NULL OR COALESCE(c.credit, 0) > 0)
                ) d WHERE 1 = 1
                """);
        if (debtOnly) {
            sql.append(" AND d.balance > 0");
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (lower(d.first_name || ' ' || d.last_name) LIKE :like ESCAPE '\\' OR lower(d.last_name || ' ' || d.first_name) LIKE :like ESCAPE '\\'"
                    + " OR lower(d.patient_code) LIKE :like ESCAPE '\\')");
            params.addValue("like", "%" + search.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        sql.append(switch (sort == null ? "" : sort) {
            case "name" -> " ORDER BY lower(d.last_name), lower(d.first_name)";
            case "lastPayment" -> " ORDER BY d.last_payment ASC NULLS FIRST";
            default -> " ORDER BY d.balance DESC, lower(d.last_name)";
        });
        return jdbc.query(sql.toString(), params, (rs, i) -> new DebtRow(rs.getObject("id", UUID.class), rs.getString("patient_code"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"), rs.getBigDecimal("fees"),
                rs.getBigDecimal("paid"), rs.getBigDecimal("balance"), rs.getBigDecimal("credit"),
                rs.getObject("last_invoice", LocalDate.class), rs.getObject("last_payment", LocalDate.class)));
    }

    // ── Dashboard ──
    public BigDecimal production(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT COALESCE(sum(total), 0) FROM invoices WHERE practice_id = :practice AND status <> 'CANCELLED' AND issue_date BETWEEN :from AND :to",
                p(practice, from, to), BigDecimal.class);
    }

    public BigDecimal collected(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT COALESCE(sum(amount), 0) FROM receipts WHERE practice_id = :practice AND voided_at IS NULL AND receipt_date BETWEEN :from AND :to",
                p(practice, from, to), BigDecimal.class);
    }

    public BigDecimal totalOwed(UUID practice) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(i.total - COALESCE(x.paid, 0)), 0)
                FROM invoices i LEFT JOIN (SELECT invoice_id, sum(amount) AS paid FROM payments GROUP BY invoice_id) x ON x.invoice_id = i.id
                WHERE i.practice_id = :practice AND i.status <> 'CANCELLED'
                """, new MapSqlParameterSource("practice", practice), BigDecimal.class);
    }

    public List<Breakdown> collectionsByMethod(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT method AS k, sum(amount) AS v FROM receipts WHERE practice_id = :practice AND voided_at IS NULL AND receipt_date BETWEEN :from AND :to GROUP BY method ORDER BY v DESC",
                p(practice, from, to), (rs, i) -> new Breakdown(rs.getString("k"), rs.getString("k"), rs.getBigDecimal("v")));
    }

    public List<Breakdown> expensesByCategory(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT c.code AS k, c.name_fr AS label, c.kind AS kind, sum(e.amount) AS v
                FROM expenses e JOIN expense_categories c ON c.id = e.category_id
                WHERE e.practice_id = :practice AND e.status <> 'CANCELLED' AND e.expense_date BETWEEN :from AND :to
                GROUP BY c.code, c.name_fr, c.kind, c.display_order ORDER BY v DESC
                """, p(practice, from, to), (rs, i) -> new Breakdown(rs.getString("k") + "|" + rs.getString("kind"), rs.getString("label"), rs.getBigDecimal("v")));
    }

    public List<Breakdown> productionByPractitioner(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT COALESCE(CAST(i.practitioner_id AS TEXT), '-') AS k, COALESCE(p.display_name, '—') AS label, sum(i.total) AS v
                FROM invoices i LEFT JOIN practitioners p ON p.id = i.practitioner_id
                WHERE i.practice_id = :practice AND i.status <> 'CANCELLED' AND i.issue_date BETWEEN :from AND :to
                GROUP BY i.practitioner_id, p.display_name ORDER BY v DESC
                """, p(practice, from, to), (rs, i) -> new Breakdown(rs.getString("k"), rs.getString("label"), rs.getBigDecimal("v")));
    }

    public List<Breakdown> collectionsByPractitioner(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT COALESCE(CAST(r.practitioner_id AS TEXT), '-') AS k, COALESCE(p.display_name, '—') AS label, sum(r.amount) AS v
                FROM receipts r LEFT JOIN practitioners p ON p.id = r.practitioner_id
                WHERE r.practice_id = :practice AND r.voided_at IS NULL AND r.receipt_date BETWEEN :from AND :to
                GROUP BY r.practitioner_id, p.display_name ORDER BY v DESC
                """, p(practice, from, to), (rs, i) -> new Breakdown(rs.getString("k"), rs.getString("label"), rs.getBigDecimal("v")));
    }

    /** One row per month in the range with production, collections and expenses; months with nothing still appear. */
    public List<MonthPoint> trend(UUID practice, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT to_char(m.month, 'YYYY-MM') AS month,
                       COALESCE((SELECT sum(total) FROM invoices WHERE practice_id = :practice AND status <> 'CANCELLED'
                                 AND date_trunc('month', issue_date) = m.month), 0) AS production,
                       COALESCE((SELECT sum(amount) FROM receipts WHERE practice_id = :practice AND voided_at IS NULL
                                 AND date_trunc('month', receipt_date) = m.month), 0) AS collections,
                       COALESCE((SELECT sum(amount) FROM expenses WHERE practice_id = :practice AND status <> 'CANCELLED'
                                 AND date_trunc('month', expense_date) = m.month), 0) AS expenses
                FROM generate_series(date_trunc('month', CAST(:from AS date)), date_trunc('month', CAST(:to AS date)), interval '1 month') AS m(month)
                ORDER BY m.month
                """, p(practice, from, to), (rs, i) -> new MonthPoint(rs.getString("month"), rs.getBigDecimal("production"),
                rs.getBigDecimal("collections"), rs.getBigDecimal("expenses")));
    }

    /** Expected takings of a day by method: what the cash close is compared against. */
    public Map<PaymentMethod, BigDecimal> expectedByMethod(UUID practice, LocalDate day) {
        Map<PaymentMethod, BigDecimal> out = new java.util.EnumMap<>(PaymentMethod.class);
        jdbc.query("SELECT method, sum(amount) AS v FROM receipts WHERE practice_id = :practice AND voided_at IS NULL AND receipt_date = :day GROUP BY method",
                new MapSqlParameterSource("practice", practice).addValue("day", day),
                rs -> {
                    out.put(PaymentMethod.valueOf(rs.getString("method")), rs.getBigDecimal("v"));
                });
        return out;
    }
}
