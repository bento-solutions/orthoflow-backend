package com.orthoflow.voice.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One dictated examination: "start examination" … "end examination". Grouping
 * commands into a session is what lets the doctor review a whole consultation
 * as a single summary and confirm it once, instead of confirming forty
 * individual writes while their hands are in a patient's mouth.
 */
@Entity
@Table(name = "voice_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoiceSession {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VoiceSessionStatus status = VoiceSessionStatus.ACTIVE;

    @Column(length = 12)
    private String locale;

    /**
     * The summary as the doctor confirmed it, frozen at confirmation time — a
     * later edit to an underlying finding must not silently rewrite what was
     * signed off.
     */
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (startedAt == null) startedAt = OffsetDateTime.now();
    }
}
