package com.orthoflow.billing.infrastructure.adapter.patient;

import com.orthoflow.patient.application.port.InvoiceLinkGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Billing's side of {@link InvoiceLinkGuard}: the patient module asks whether a
 * patient still has financial records, billing answers, and neither imports the
 * other's domain types.
 *
 * <p>"Financial records" is wider than invoices now: receipts, instalment plans,
 * cheques and fee notes all carry the same accounting-law retention duty and all
 * reference the patient with {@code ON DELETE RESTRICT}, so an erasure has to be
 * refused for any of them, with the real reason, rather than failing on a foreign key.
 */
@Component
@RequiredArgsConstructor
public class InvoiceLinkGuardAdapter implements InvoiceLinkGuard {

    private final JdbcTemplate jdbc;

    @Override
    public long countInvoicesForPatient(UUID patientId) {
        Long count = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM invoices WHERE patient_id = ?)
                     + (SELECT count(*) FROM receipts WHERE patient_id = ?)
                     + (SELECT count(*) FROM payment_plans WHERE patient_id = ?)
                     + (SELECT count(*) FROM cheques WHERE patient_id = ?)
                     + (SELECT count(*) FROM tax_documents WHERE patient_id = ?)
                """, Long.class, patientId, patientId, patientId, patientId, patientId);
        return count == null ? 0 : count;
    }
}
