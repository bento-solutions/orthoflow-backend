package com.orthoflow.retrocession.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The terms on which one practitioner is paid for a period of time. A change of
 * terms is a new rule with a later {@code effectiveFrom}; the database refuses two
 * rules for the same practitioner that overlap in time, so "which rule applied on
 * this day" always has one answer.
 */
@Entity
@Table(name = "retrocession_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrocessionRule {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(name = "practitioner_id", nullable = false)
    private UUID practitionerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Basis basis;

    @Column(name = "rate_percent", nullable = false)
    private BigDecimal ratePercent;

    @Column(name = "deduct_lab_fees", nullable = false)
    private boolean deductLabFees;

    @Column(name = "fixed_monthly_amount", nullable = false)
    @Builder.Default
    private BigDecimal fixedMonthlyAmount = BigDecimal.ZERO;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Per treatment category, a percentage that replaces {@link #ratePercent}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "retrocession_rule_overrides", joinColumns = @JoinColumn(name = "rule_id"))
    @MapKeyColumn(name = "category")
    @Column(name = "rate_percent", nullable = false)
    @Builder.Default
    private Map<String, BigDecimal> overrides = new HashMap<>();

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
