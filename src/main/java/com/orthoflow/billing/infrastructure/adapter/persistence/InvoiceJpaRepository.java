package com.orthoflow.billing.infrastructure.adapter.persistence;

import com.orthoflow.billing.domain.model.Invoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceJpaRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findByPatientId(UUID patientId);
    Page<Invoice> findByPatientId(UUID patientId, Pageable pageable);
    long countByPatientId(UUID patientId);

    /**
     * Row-locking read for {@code recordPayment} / {@code cancelInvoice}: two
     * concurrent payments must not each read the same "already paid" total,
     * both pass the outstanding-balance check and overpay (audit M2). Callers
     * are already inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);

    // ── Billing summary: aggregates, not a findAll() + in-memory reduce ──
    // getBillingSummary() used to load every invoice (plus its lines and
    // payments, N+1) on every dashboard hit (audit M1).

    @Query("SELECT COALESCE(SUM(i.total), 0) FROM Invoice i WHERE i.issueDate BETWEEN :start AND :end")
    BigDecimal sumTotalIssuedBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
            WHERE p.invoice.issueDate BETWEEN :start AND :end
            """)
    BigDecimal sumPaymentsForInvoicesIssuedBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT i.status AS status, COUNT(i) AS count FROM Invoice i GROUP BY i.status")
    List<StatusCount> countGroupedByStatus();

    /** Projection for {@link #countGroupedByStatus()}. */
    interface StatusCount {
        com.orthoflow.billing.domain.model.InvoiceStatus getStatus();
        long getCount();
    }
}
