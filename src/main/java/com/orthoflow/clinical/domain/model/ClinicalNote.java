package com.orthoflow.clinical.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Free-text clinical narrative — the part of a consultation that does not fit
 * a structured field. Soft-deleted only, for the same reason findings are:
 * a note that can vanish without trace is not a clinical record.
 */
@Entity
@Table(name = "clinical_notes")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClinicalNote {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private NoteCategory category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Non-null only when the doctor explicitly attached the note to a tooth. */
    @Column(length = 3)
    private String fdi;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String source = "manual";

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
