package com.orthoflow.billing.infrastructure.adapter.persistence;

import com.orthoflow.billing.domain.model.Receipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptJpaRepository extends JpaRepository<Receipt, UUID> {

    Optional<Receipt> findByIdAndPracticeId(UUID id, UUID practiceId);

    /** Row-locked, like invoices: two people spending the same credit must not both see it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Receipt r WHERE r.id = :id")
    Optional<Receipt> findByIdForUpdate(@Param("id") UUID id);

    List<Receipt> findByPatientIdOrderByReceiptDateDescCreatedAtDesc(UUID patientId);

    /** The patient's receipts that still have money to spend, oldest first, locked. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM Receipt r
            WHERE r.patientId = :patientId AND r.voidedAt IS NULL
              AND r.amount > (SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.receiptId = r.id)
            ORDER BY r.receiptDate, r.createdAt
            """)
    List<Receipt> withCreditForUpdate(@Param("patientId") UUID patientId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.receiptId = :receiptId")
    BigDecimal allocatedAmount(@Param("receiptId") UUID receiptId);
}
