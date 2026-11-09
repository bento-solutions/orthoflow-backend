package com.orthoflow.treatment.infrastructure.adapter.persistence;

import com.orthoflow.treatment.domain.model.TreatmentInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TreatmentInvoiceJpaRepository extends JpaRepository<TreatmentInvoice, UUID> {
    Optional<TreatmentInvoice> findByInvoiceNumber(String invoiceNumber);
    List<TreatmentInvoice> findByPatientId(UUID patientId);

    @org.springframework.data.jpa.repository.Query("""
            SELECT t.id, t.name, COALESCE(SUM(i.treatmentPrice), 0), COALESCE(SUM(i.consumablesCost), 0), COUNT(i)
            FROM TreatmentInvoice i JOIN i.treatment t
            WHERE i.status = com.orthoflow.treatment.domain.model.TreatmentInvoiceStatus.FINALIZED
            GROUP BY t.id, t.name ORDER BY t.name
            """)
    java.util.List<Object[]> finalizedTotalsByTreatment();
}
