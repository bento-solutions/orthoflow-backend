package com.orthoflow.voice.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only record of one interpreted voice command — what was heard, what
 * it was understood to mean, whether it was allowed to run, and what it
 * changed.
 *
 * <p>Rows are written for rejected, failed and clarification-needed commands
 * too. The record of what the system heard and deliberately did <em>not</em>
 * do is the half that matters when a clinical entry is later disputed.
 *
 * <p>{@code transcript} may be null by policy rather than by accident: audit
 * XII.5 advises against retaining raw utterances next to patient identifiers,
 * so retention is a per-deployment setting
 * ({@code orthoflow.voice.audit.store-transcript}). The interpreted command is
 * always retained; only the audio-derived text is optional.
 */
@Entity
@Table(name = "voice_command_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoiceCommandAudit {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(columnDefinition = "TEXT")
    private String transcript;

    @Column(length = 12)
    private String locale;

    @Column(nullable = false, length = 64)
    private String intent;

    /** Resolved entities as a JSON object string. */
    @Column(columnDefinition = "TEXT")
    private String entities;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResolverKind resolver;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(length = 40)
    private String module;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tier", nullable = false, length = 16)
    private RiskTier riskTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_status", nullable = false, length = 16)
    private ConfirmationStatus confirmationStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CommandOutcome outcome;

    @Column(name = "target_type", length = 48)
    private String targetType;

    /**
     * The record(s) this command created or changed. A dictated finding set is
     * one command and one audit row, so this holds the joined ids of every
     * finding it produced — see V17 for why it is unbounded.
     */
    @Column(name = "target_id", columnDefinition = "TEXT")
    private String targetId;

    @Column(name = "previous_value", columnDefinition = "TEXT")
    private String previousValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "undone_at")
    private OffsetDateTime undoneAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (occurredAt == null) occurredAt = OffsetDateTime.now();
    }
}
