package com.orthoflow.billing.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_plan_instalments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentPlanInstalment {

    public enum Status { PENDING, PAID, CANCELLED }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id")
    private PaymentPlan plan;

    /** 0 is the down payment; 1..n are the instalments. */
    @Column(nullable = false)
    private int seq;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "receipt_id")
    private UUID receiptId;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public BigDecimal remaining() {
        return amount.subtract(paidAmount);
    }
}
