package com.orthoflow.consultation.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One recorded conversation between the doctor and a patient, from the first
 * word to the saved record.
 *
 * <p>The transcript and the extraction are both kept as plain text on this row
 * while the consultation is open, so a browser that dies mid-consultation loses
 * nothing. Neither is the clinical record: the extraction is a proposal, and the
 * transcript only reaches the patient's file, as a note, when the doctor saves.
 *
 * <p>{@code @DynamicUpdate}: a save writes only the columns it changed. The
 * review state is written by its own update query while readings and phase
 * changes save the row, and a full-row update from an entity read a moment
 * earlier would put the previous review state back.
 */
@Entity
@DynamicUpdate
@Table(name = "consultations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Consultation {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Version
    private Long version;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    /** The dictated-examination session that carries this consultation's chart commands. */
    @Column(name = "voice_session_id")
    private UUID voiceSessionId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConsultationStatus status = ConsultationStatus.INTAKE;

    @Column(length = 12)
    private String locale;

    /** When the doctor attested the patient was told the conversation is transcribed and kept. */
    @Column(name = "patient_informed_at", nullable = false)
    private OffsetDateTime patientInformedAt;

    @Column(columnDefinition = "TEXT")
    private String transcript;

    /** The latest extraction, as JSON. A proposal. */
    @Column(columnDefinition = "TEXT")
    private String draft;

    /** What the doctor validated at save, as JSON — so the documents can be printed again as signed. */
    @Column(columnDefinition = "TEXT")
    private String reviewed;

    @Column(columnDefinition = "TEXT")
    private String report;

    /**
     * The doctor's decisions on the panel so far (JSON, the browser's own
     * shape), so a reload does not undo a review. Not the record. Written by
     * {@code ConsultationRepository#saveReviewState}, which does not move the version.
     */
    @Column(name = "review_state", columnDefinition = "TEXT")
    private String reviewState;

    /** When anything last happened; an open consultation idle long enough is discarded. */
    @Column(name = "last_activity_at")
    private OffsetDateTime lastActivityAt;

    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "examination_started_at")
    private OffsetDateTime examinationStartedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    /**
     * Set each time a save begins. Changing a field is what bumps the version
     * (an unchanged entity is not written at all), so this is how a second,
     * concurrent save is turned away with an optimistic-lock conflict.
     */
    @Column(name = "save_started_at")
    private OffsetDateTime saveStartedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (startedAt == null) startedAt = OffsetDateTime.now();
        if (lastActivityAt == null) lastActivityAt = startedAt;
    }

    /** Something happened on it: it is not abandoned. */
    public void touch() {
        lastActivityAt = OffsetDateTime.now();
    }
}
