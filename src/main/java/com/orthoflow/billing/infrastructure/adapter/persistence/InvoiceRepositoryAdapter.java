package com.orthoflow.billing.infrastructure.adapter.persistence;

import com.orthoflow.billing.domain.model.Invoice;
import com.orthoflow.billing.domain.model.InvoiceStatus;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class InvoiceRepositoryAdapter implements InvoiceRepository {

    private final InvoiceJpaRepository jpaRepository;

    @Override
    public Invoice save(Invoice invoice) {
        return jpaRepository.save(invoice);
    }

    @Override
    public Optional<Invoice> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Invoice> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id);
    }

    @Override
    public List<Invoice> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public List<Invoice> findByPatientId(UUID patientId) {
        return jpaRepository.findByPatientId(patientId);
    }

    @Override
    public Page<Invoice> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable);
    }

    @Override
    public Page<Invoice> findByPatientId(UUID patientId, Pageable pageable) {
        return jpaRepository.findByPatientId(patientId, pageable);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }

    @Override
    public BigDecimal sumTotalIssuedBetween(LocalDate start, LocalDate end) {
        BigDecimal sum = jpaRepository.sumTotalIssuedBetween(start, end);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    @Override
    public BigDecimal sumPaymentsForInvoicesIssuedBetween(LocalDate start, LocalDate end) {
        BigDecimal sum = jpaRepository.sumPaymentsForInvoicesIssuedBetween(start, end);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    @Override
    public Map<InvoiceStatus, Long> countByStatus() {
        Map<InvoiceStatus, Long> result = new EnumMap<>(InvoiceStatus.class);
        for (InvoiceJpaRepository.StatusCount row : jpaRepository.countGroupedByStatus()) {
            if (row.getStatus() != null) {
                result.put(row.getStatus(), row.getCount());
            }
        }
        return result;
    }
}
