package com.orthoflow.clinical.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One statement about the gums of one region on one date. Append-only: the
 * current state of a region is its latest assessment, and the earlier ones are
 * the periodontal history, so "gingivitis, treated, now healthy" stays visible.
 */
@Entity
@Table(name = "periodontal_assessments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PeriodontalAssessment {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PerioRegion region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, name = "condition", length = 16)
    private PerioCondition condition;

    /** Periodontitis stage I-IV (2018 classification); only for PERIODONTITIS. */
    private Integer stage;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "assessed_on", nullable = false)
    private LocalDate assessedOn;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String source = "manual";

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
