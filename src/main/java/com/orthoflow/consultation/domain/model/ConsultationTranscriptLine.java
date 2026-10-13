package com.orthoflow.consultation.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One utterance of a consultation, with the time it was heard. Written once and
 * never edited: the log is only worth reading if what it says cannot have been
 * tidied afterwards.
 */
@Entity
@Table(name = "consultation_transcript_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultationTranscriptLine {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "consultation_id", nullable = false, updatable = false)
    private UUID consultationId;

    /** The browser's running number for the line; unique within the consultation. */
    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(name = "spoken_at", nullable = false, updatable = false)
    private OffsetDateTime spokenAt;

    /** The server's clock when the line arrived. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 12)
    private Phase phase;

    @Column(nullable = false, updatable = false)
    private boolean typed;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String text;

    public enum Phase { INTAKE, EXAMINATION }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (recordedAt == null) recordedAt = OffsetDateTime.now();
    }
}
