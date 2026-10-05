package com.orthoflow.billing.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A cheque in the register. It is received (PENDING), deposited, then cashed —
 * or it bounces, which reverses the receipt it was taken against. A guarantee
 * cheque is held as security and never becomes a receipt unless it is deposited.
 */
@Entity
@Table(name = "cheques")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cheque {

    public enum Status { PENDING, DEPOSITED, CASHED, REJECTED }

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(nullable = false)
    private String number;

    private String bank;

    @Column(name = "drawer_name")
    private String drawerName;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "deposit_date")
    private LocalDate depositDate;

    @Column(name = "cashed_date")
    private LocalDate cashedDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "is_guarantee", nullable = false)
    private boolean guarantee;

    @Column(name = "receipt_id")
    private UUID receiptId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
