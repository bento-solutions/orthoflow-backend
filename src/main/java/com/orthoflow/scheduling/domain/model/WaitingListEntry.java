package com.orthoflow.scheduling.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Someone who needs a slot and has none yet: a patient, a kind of visit, a
 * duration, when they can come and how urgent it is. Scheduling it creates the
 * appointment and closes the entry.
 */
@Entity
@Table(name = "waiting_list_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaitingListEntry {

    public enum Urgency { LOW, NORMAL, HIGH, URGENT }

    public enum Status { WAITING, SCHEDULED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "appointment_type_id")
    private UUID appointmentTypeId;

    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(name = "duration_minutes", nullable = false)
    @Builder.Default
    private int durationMinutes = 30;

    /** ISO weekdays, comma separated ("1,3,5"); null means any day. */
    @Column(name = "preferred_weekdays")
    private String preferredWeekdays;

    @Column(name = "preferred_from")
    private LocalTime preferredFrom;

    @Column(name = "preferred_to")
    private LocalTime preferredTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Urgency urgency = Urgency.NORMAL;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.WAITING;

    @Column(name = "scheduled_appointment_id")
    private UUID scheduledAppointmentId;

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
