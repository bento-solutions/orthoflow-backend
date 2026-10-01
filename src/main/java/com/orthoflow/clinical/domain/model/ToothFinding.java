package com.orthoflow.clinical.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One clinical fact about one tooth. A tooth carries as many of these as the
 * examination produced: "old crown, recurrent caries underneath, crown needs
 * replacement" is three findings, and recording it as a single status would
 * discard two-thirds of what the doctor said.
 *
 * <p>{@link ToothState#getStatus()} remains the one status the 2D and 3D
 * charts colour themselves from, and is now derived from the active findings
 * here rather than being written directly.
 */
@Entity
@Table(name = "tooth_findings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToothFinding {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chart_id", nullable = false)
    private DentalChart chart;

    @Column(nullable = false, length = 3)
    private String fdi;

    /** Canonical code from the shared lexicon, e.g. {@code recurrent_caries}. */
    @Column(name = "finding_code", nullable = false, length = 48)
    private String findingCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private FindingKind kind;

    /**
     * Optional tooth surface: occlusal, mesial, distal, buccal, lingual,
     * incisal or cervical — or up to three of them joined by hyphens
     * ("mesial-occlusal-distal"). A compound cavity is said that way and must
     * not be reduced to one of its faces.
     */
    @Column(length = 48)
    private String surface;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Severity severity;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FindingStatus status = FindingStatus.ACTIVE;

    /** '2d', '3d_top', 'voice', 'manual'… — same vocabulary as ToothStateEvent. */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String source = "manual";

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    /** The dictated examination this finding came from, when there was one. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
