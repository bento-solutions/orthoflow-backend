package com.orthoflow.billing.domain.repository;

import com.orthoflow.billing.domain.model.Invoice;
import com.orthoflow.billing.domain.model.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository {
    Invoice save(Invoice invoice);
    Optional<Invoice> findById(UUID id);
    /** Row-locked read for money mutations — see the JPA repo's javadoc (audit M2). */
    Optional<Invoice> findByIdForUpdate(UUID id);
    List<Invoice> findAll();
    Page<Invoice> findAll(Pageable pageable);
    List<Invoice> findByPatientId(UUID patientId);
    Page<Invoice> findByPatientId(UUID patientId, Pageable pageable);
    void deleteById(UUID id);

    // Billing-summary aggregates (audit M1).
    long count();
    BigDecimal sumTotalIssuedBetween(LocalDate start, LocalDate end);
    BigDecimal sumPaymentsForInvoicesIssuedBetween(LocalDate start, LocalDate end);
    Map<InvoiceStatus, Long> countByStatus();
}
