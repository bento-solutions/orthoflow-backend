package com.orthoflow.inventory.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * "We used more of this than the recipe says, and here is why."
 *
 * <p>One row per explained excess on a treatment. It is not a column on the movement
 * because a single movement is usually part expected and part excess, and only the
 * excess needs an explanation.
 *
 * <p>{@code patientTreatmentId} is a plain id rather than a JPA relation: the treatment
 * module owns that entity, and inventory reads it by id the way the rest of the
 * cross-module boundaries here already work.
 */
@Entity
@Table(name = "stock_consumption_variances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockConsumptionVariance {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "patient_treatment_id", nullable = false)
    private UUID patientTreatmentId;

    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    /** Always positive: how much was used over and above the bill of materials. */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VarianceReason reason;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
