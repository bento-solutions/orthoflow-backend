package com.orthoflow.treatment.domain.repository;

import com.orthoflow.treatment.domain.model.TreatmentInvoice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TreatmentInvoiceRepository {
    TreatmentInvoice save(TreatmentInvoice treatmentInvoice);
    Optional<TreatmentInvoice> findById(UUID id);
    Optional<TreatmentInvoice> findByInvoiceNumber(String invoiceNumber);
    List<TreatmentInvoice> findAll();

    /** Revenue, material cost and sessions of the finalised invoices, per treatment, summed by the database. */
    record TreatmentTotals(UUID treatmentId, String treatmentName, java.math.BigDecimal revenue, java.math.BigDecimal materialCost,
                           long sessions) {
    }

    List<TreatmentTotals> finalizedTotalsByTreatment();
    List<TreatmentInvoice> findByPatientId(UUID patientId);
    void deleteById(UUID id);
}
