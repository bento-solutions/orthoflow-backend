package com.orthoflow.clinical.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "patient_allergies")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientAllergy {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(nullable = false, length = 160)
    private String substance;

    @Column(columnDefinition = "TEXT")
    private String reaction;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Severity severity;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String source = "manual";

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (recordedAt == null) recordedAt = OffsetDateTime.now();
    }
}
