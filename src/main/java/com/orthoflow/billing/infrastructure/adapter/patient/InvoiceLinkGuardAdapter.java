package com.orthoflow.billing.infrastructure.adapter.patient;

import com.orthoflow.billing.infrastructure.adapter.persistence.InvoiceJpaRepository;
import com.orthoflow.patient.application.port.InvoiceLinkGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Billing's side of {@link InvoiceLinkGuard}: the patient module asks whether a
 * patient still has invoices, billing answers, and neither imports the other's
 * domain types.
 */
@Component
@RequiredArgsConstructor
public class InvoiceLinkGuardAdapter implements InvoiceLinkGuard {

    private final InvoiceJpaRepository invoiceJpaRepository;

    @Override
    public long countInvoicesForPatient(UUID patientId) {
        return invoiceJpaRepository.countByPatientId(patientId);
    }
}
