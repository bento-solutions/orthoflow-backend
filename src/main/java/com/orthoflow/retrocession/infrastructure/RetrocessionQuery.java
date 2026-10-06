package com.orthoflow.retrocession.infrastructure;

import com.orthoflow.billing.domain.model.InvoiceStatus;
import com.orthoflow.billing.domain.model.PaymentMethod;
import com.orthoflow.retrocession.application.service.RetrocessionCalculator.Item;
import com.orthoflow.retrocession.application.service.RetrocessionCalculator.LabFee;
import com.orthoflow.retrocession.domain.model.Basis;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The figures retrocessions are computed from, read straight from the billing
 * and lab tables. Plain SQL because each row is a payment or an invoice line
 * apportioned by share, which an entity graph would only load one row at a time.
 *
 * <p>Both bases are expressed per invoice line so a category override can apply.
 * A payment is spread over the invoice's lines in proportion to their net amounts
 * (payment ÷ invoice total × line total), which also takes any tax out of it.
 */
@Component
@RequiredArgsConstructor
public class RetrocessionQuery {

    private final NamedParameterJdbcTemplate jdbc;

    public record UnattributedRow(UUID invoiceId, String invoiceNumber, LocalDate date, BigDecimal amount) {
    }

    public record OutstandingAdvance(UUID id, LocalDate date, BigDecimal amount, BigDecimal outstanding) {
    }

    /**
     * @param attributed true for invoices that have a practitioner, false for the ones that do not
     * @param practitionerId restricts to one practitioner when attributed; ignored otherwise
     */
    public List<Item> items(UUID practice, Basis basis, LocalDate from, LocalDate to, Collection<PaymentMethod> methods,
                            Collection<InvoiceStatus> statuses, boolean attributed, UUID practitionerId) {
        MapSqlParameterSource params = new MapSqlParameterSource("practice", practice).addValue("from", from).addValue("to", to);
        StringBuilder sql = new StringBuilder();
        if (basis == Basis.COLLECTED) {
            sql.append("""
                    SELECT pay.payment_date AS d, i.id AS invoice_id, i.invoice_number, i.practitioner_id, pa.patient_code,
                           COALESCE(t.category, '') AS category,
                           CASE WHEN il.id IS NULL OR i.total IS NULL OR i.total = 0 THEN pay.amount
                                ELSE pay.amount * il.line_total / i.total END AS amount
                    FROM payments pay
                    JOIN invoices i ON i.id = pay.invoice_id
                    JOIN patients pa ON pa.id = i.patient_id
                    LEFT JOIN receipts r ON r.id = pay.receipt_id
                    LEFT JOIN invoice_lines il ON il.invoice_id = i.id
                    LEFT JOIN treatments t ON t.code = il.act_code
                    WHERE i.practice_id = :practice AND (r.id IS NULL OR r.voided_at IS NULL)
                      AND pay.payment_date BETWEEN :from AND :to
                    """);
            if (methods != null && !methods.isEmpty()) {
                sql.append(" AND pay.method IN (:methods)");
                params.addValue("methods", methods.stream().map(Enum::name).toList());
            }
        } else {
            sql.append("""
                    SELECT i.issue_date AS d, i.id AS invoice_id, i.invoice_number, i.practitioner_id, pa.patient_code,
                           COALESCE(t.category, '') AS category,
                           COALESCE(il.line_total, i.total - COALESCE(i.tax_amount, 0)) AS amount
                    FROM invoices i
                    JOIN patients pa ON pa.id = i.patient_id
                    LEFT JOIN invoice_lines il ON il.invoice_id = i.id
                    LEFT JOIN treatments t ON t.code = il.act_code
                    WHERE i.practice_id = :practice AND i.issue_date BETWEEN :from AND :to
                    """);
        }
        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" AND i.status IN (:statuses)");
            params.addValue("statuses", statuses.stream().map(Enum::name).toList());
        } else {
            sql.append(" AND i.status <> 'CANCELLED'");
        }
        if (attributed) {
            sql.append(" AND i.practitioner_id IS NOT NULL");
            if (practitionerId != null) {
                sql.append(" AND i.practitioner_id = :practitioner");
                params.addValue("practitioner", practitionerId);
            }
        } else {
            sql.append(" AND i.practitioner_id IS NULL");
        }
        return jdbc.query(sql.toString(), params, (rs, n) -> new Item(basis, rs.getObject("d", LocalDate.class),
                rs.getObject("practitioner_id", UUID.class), rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"),
                rs.getString("patient_code"), rs.getString("category"), rs.getBigDecimal("amount")));
    }

    /** Lab bills received in the period, the same moment the expense is booked. */
    public List<LabFee> labFees(UUID practice, LocalDate from, LocalDate to, UUID practitionerId) {
        MapSqlParameterSource params = new MapSqlParameterSource("practice", practice).addValue("from", from).addValue("to", to);
        String filter = "";
        if (practitionerId != null) {
            filter = " AND lo.practitioner_id = :practitioner";
            params.addValue("practitioner", practitionerId);
        }
        return jdbc.query("""
                SELECT lo.id, lo.received_date AS d, lo.practitioner_id, lo.cost, lo.item_type, pa.patient_code
                FROM lab_orders lo JOIN patients pa ON pa.id = lo.patient_id
                WHERE lo.practice_id = :practice AND lo.practitioner_id IS NOT NULL AND lo.cost > 0
                  AND lo.received_date BETWEEN :from AND :to""" + filter, params,
                (rs, n) -> new LabFee(rs.getObject("d", LocalDate.class), rs.getObject("practitioner_id", UUID.class),
                        rs.getObject("id", UUID.class), "Lab: " + rs.getString("item_type"), rs.getString("patient_code"),
                        rs.getBigDecimal("cost")));
    }

    /** Unattributed invoices, summed per invoice, biggest first. */
    public List<UnattributedRow> unattributed(UUID practice, Basis basis, LocalDate from, LocalDate to,
                                              Collection<PaymentMethod> methods, Collection<InvoiceStatus> statuses) {
        return items(practice, basis, from, to, methods, statuses, false, null).stream()
                .collect(java.util.stream.Collectors.groupingBy(Item::invoiceId))
                .values().stream()
                .map(group -> new UnattributedRow(group.get(0).invoiceId(), group.get(0).invoiceNumber(),
                        group.stream().map(Item::date).min(LocalDate::compareTo).orElseThrow(),
                        group.stream().map(Item::amount).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .sorted(java.util.Comparator.comparing(UnattributedRow::amount).reversed())
                .toList();
    }

    /** Advances given on or before {@code upTo} and not yet fully settled by a live statement, oldest first. */
    public List<OutstandingAdvance> outstandingAdvances(UUID practice, UUID practitionerId, LocalDate upTo) {
        return jdbc.query("""
                SELECT a.id, a.advance_date, a.amount, a.amount - COALESCE(s.settled, 0) AS outstanding
                FROM retrocession_advances a
                LEFT JOIN (SELECT st.advance_id, sum(st.amount) AS settled
                           FROM retrocession_advance_settlements st
                           JOIN retrocession_statements x ON x.id = st.statement_id AND x.voided_at IS NULL
                           GROUP BY st.advance_id) s ON s.advance_id = a.id
                WHERE a.practice_id = :practice AND a.practitioner_id = :practitioner AND a.advance_date <= :upTo
                  AND a.amount - COALESCE(s.settled, 0) > 0
                ORDER BY a.advance_date, a.created_at, a.id""",
                new MapSqlParameterSource("practice", practice).addValue("practitioner", practitionerId).addValue("upTo", upTo),
                (rs, n) -> new OutstandingAdvance(rs.getObject("id", UUID.class), rs.getObject("advance_date", LocalDate.class),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("outstanding")));
    }
}
