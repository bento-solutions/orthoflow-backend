package com.orthoflow.retrocession.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Money handed to a collaborator ahead of a statement. Validating a statement
 * settles outstanding advances oldest first; how much of one is still
 * outstanding is derived from those settlements rather than stored here.
 */
@Entity
@Table(name = "retrocession_advances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrocessionAdvance {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(name = "practitioner_id", nullable = false)
    private UUID practitionerId;

    @Column(name = "advance_date", nullable = false)
    private LocalDate advanceDate;

    @Column(nullable = false)
    private BigDecimal amount;

    private String method;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
