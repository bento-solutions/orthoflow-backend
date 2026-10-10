package com.orthoflow.prescription.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An ordonnance as issued: numbered, rendered once and kept, with the allergy warnings
 * the prescriber saw and went past. Voiding keeps the row; nothing is ever re-rendered.
 */
@Entity
@Table(name = "prescriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prescription {

    public enum Status { ISSUED, VOID }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(nullable = false)
    private String number;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(name = "consultation_id")
    private UUID consultationId;

    @Column(name = "template_id")
    private UUID templateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String lines;

    @Column(columnDefinition = "TEXT")
    private String advice;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allergy_warnings", columnDefinition = "jsonb")
    private String allergyWarnings;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.ISSUED;

    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (issuedAt == null) issuedAt = OffsetDateTime.now();
    }
}
