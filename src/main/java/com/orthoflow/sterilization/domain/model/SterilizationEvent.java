package com.orthoflow.sterilization.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One line of the sterilization register. Append-only: nothing in the application edits or deletes these. */
@Entity
@Table(name = "sterilization_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SterilizationEvent {

    public enum Action { REGISTERED, USED, DIRTY, PROCESSED, RELEASED, RECALLED, LUBRICATED, RETIRED }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state")
    private SterilizationItem.State fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state")
    private SterilizationItem.State toState;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "performed_by")
    private UUID performedBy;

    @Column(name = "cycle_id")
    private UUID cycleId;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(columnDefinition = "TEXT")
    private String note;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (occurredAt == null) occurredAt = OffsetDateTime.now();
    }
}
