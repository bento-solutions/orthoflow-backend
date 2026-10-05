package com.orthoflow.billing.infrastructure.adapter.persistence;

import com.orthoflow.billing.domain.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByInvoiceId(UUID invoiceId);

    @Query("SELECT p FROM Payment p WHERE p.receiptId = :receiptId")
    List<Payment> findByReceiptId(@Param("receiptId") UUID receiptId);
}
