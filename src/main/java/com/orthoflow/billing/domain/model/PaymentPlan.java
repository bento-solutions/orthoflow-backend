package com.orthoflow.billing.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How a treatment contract is paid: a down payment and N instalments at a
 * frequency. Optional per treatment — a clinic that charges per visit simply has
 * none. The schedule feeds the "due today" list, the debt list and reminders.
 */
@Entity
@Table(name = "payment_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentPlan {

    public enum Frequency { WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY }

    public enum Status { ACTIVE, COMPLETED, CANCELLED }

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "patient_treatment_id")
    private UUID patientTreatmentId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(nullable = false)
    private BigDecimal total;

    @Column(name = "down_payment", nullable = false)
    @Builder.Default
    private BigDecimal downPayment = BigDecimal.ZERO;

    @Column(name = "instalment_count", nullable = false)
    private int instalmentCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq ASC")
    @Builder.Default
    private List<PaymentPlanInstalment> instalments = new ArrayList<>();

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
