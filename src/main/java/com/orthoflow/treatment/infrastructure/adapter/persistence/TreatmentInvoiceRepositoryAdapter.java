package com.orthoflow.treatment.infrastructure.adapter.persistence;

import com.orthoflow.treatment.domain.model.TreatmentInvoice;
import com.orthoflow.treatment.domain.repository.TreatmentInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TreatmentInvoiceRepositoryAdapter implements TreatmentInvoiceRepository {

    private final TreatmentInvoiceJpaRepository jpaRepository;

    @Override
    public TreatmentInvoice save(TreatmentInvoice treatmentInvoice) {
        return jpaRepository.save(treatmentInvoice);
    }

    @Override
    public Optional<TreatmentInvoice> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<TreatmentInvoice> findByInvoiceNumber(String invoiceNumber) {
        return jpaRepository.findByInvoiceNumber(invoiceNumber);
    }

    @Override
    public List<TreatmentInvoice> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public List<TreatmentInvoice> findByPatientId(UUID patientId) {
        return jpaRepository.findByPatientId(patientId);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public List<TreatmentTotals> finalizedTotalsByTreatment() {
        return jpaRepository.finalizedTotalsByTreatment().stream()
                .map(r -> new TreatmentTotals((UUID) r[0], (String) r[1], (java.math.BigDecimal) r[2], (java.math.BigDecimal) r[3], ((Number) r[4]).longValue()))
                .toList();
    }
}
