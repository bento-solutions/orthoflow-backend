package com.orthoflow.billing.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Money received from a patient. What it pays for is a separate matter: the
 * rows of {@code payments} are its allocations, each the part applied to one
 * invoice, and whatever is not allocated is the patient's credit. A receipt is
 * never edited after the fact; a mistake is voided, which undoes its allocations
 * and keeps the record.
 */
@Entity
@Table(name = "receipts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Receipt {

    @Id
    private UUID id;

    @Version
    private Long version;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    /** Whose work this income is credited to, for retrocessions and per-doctor figures. */
    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(name = "cheque_id")
    private UUID chequeId;

    private String reference;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_reason", columnDefinition = "TEXT")
    private String voidReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public boolean isVoided() {
        return voidedAt != null;
    }
}
