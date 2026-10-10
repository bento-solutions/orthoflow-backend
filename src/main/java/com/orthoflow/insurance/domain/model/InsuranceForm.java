package com.orthoflow.insurance.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A care form filled for the patient's insurer: the insurer's own sheet with the
 * patient, the insured person, the practitioner and the acts written in, or OrthoFlow's
 * statement of acts when the insurer's sheet is not one OrthoFlow knows.
 *
 * <p>The PDF is rendered once and kept. {@code snapshot} holds exactly what was printed,
 * so the overlay for a numbered paper form and a reprint say the same thing even after
 * the patient's record changes; refreshing it is an explicit step while it is unprinted.
 */
@Entity
@Table(name = "insurance_forms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsuranceForm {

    /** EXECUTION reports acts done (reimbursement); PRIOR_AGREEMENT asks before treating (entente préalable, devis). */
    public enum Purpose { EXECUTION, PRIOR_AGREEMENT }

    public enum Source { CONSULTATION, MANUAL }

    public enum Status { TO_PRINT, PRINTED, HANDED_OVER, VOID }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(nullable = false)
    private String number;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "insurer_id")
    private UUID insurerId;

    /** The layout the PDF was drawn with, or "generic". */
    @Column(name = "form_code", nullable = false)
    private String formCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Column(name = "consultation_id")
    private UUID consultationId;

    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(name = "care_date", nullable = false)
    private LocalDate careDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String lines;

    @Column(nullable = false)
    private BigDecimal total;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String snapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.TO_PRINT;

    @Column(name = "file_id")
    private UUID fileId;

    /** The task that handed the form to the front desk, once it was sent. */
    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "printed_at")
    private OffsetDateTime printedAt;

    @Column(name = "handed_over_at")
    private OffsetDateTime handedOverAt;

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
