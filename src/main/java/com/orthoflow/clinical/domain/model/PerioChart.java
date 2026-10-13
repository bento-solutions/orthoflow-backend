package com.orthoflow.clinical.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The six-point probing record of one examination (see V67). The measurements are a document:
 * {@code toothStatus} and {@code teeth} hold JSON that {@code PerioChartService} validates and
 * canonicalises before it is stored, and reads back whole. {@code version} is the optimistic lock:
 * a chart open on two screens cannot silently overwrite itself.
 */
@Entity
@Table(name = "perio_charts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerioChart {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "chart_date", nullable = false)
    private LocalDate chartDate;

    @Column(name = "visit_reason", length = 200)
    private String visitReason;

    @Column(name = "clinical_notes", columnDefinition = "TEXT")
    private String clinicalNotes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tooth_status", columnDefinition = "jsonb", nullable = false)
    private String toothStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String teeth;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
