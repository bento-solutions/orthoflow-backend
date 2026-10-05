package com.orthoflow.scheduling.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "appointments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment {

    @Id
    private UUID id;

    @Version
    @Column(name = "version")
    private Long version;

    // A plain UUID rather than a @ManyToOne Patient — scheduling reads
    // patient data through PatientLookup, not by holding a JPA relation
    // into another module's entity graph (audit I.2).
    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = com.orthoflow.common.tenancy.Practices.DEFAULT_ID;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "date_time", nullable = false)
    private OffsetDateTime dateTime;

    // Nullable — a clinic that doesn't track chairs can leave every
    // appointment unassigned; the DB exclusion constraint (V21) only
    // applies where chair_id IS NOT NULL, so unassigned appointments never
    // conflict with each other on this axis (audit VIII.6 / P2 #29).
    @Column(name = "chair_id")
    private UUID chairId;

    // The clinician the visit is with. Nullable for legacy rows; the DB
    // exclusion constraint (V33) stops one practitioner being double-booked.
    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(name = "duration_minutes", nullable = false)
    @Builder.Default
    private int durationMinutes = 30;

    @Column(nullable = false)
    private String type;

    /** The catalogue entry behind {@code type}; the text stays as the label history was written with. */
    @Column(name = "appointment_type_id")
    private UUID appointmentTypeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppointmentStatus status;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "appliance_step")
    private Integer applianceStep;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "arrived_at")
    private OffsetDateTime arrivedAt;

    @Column(name = "seated_at")
    private OffsetDateTime seatedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "waiting_room_id")
    private UUID waitingRoomId;

    /** Order in the waiting room: lower is called first. */
    @Column(name = "waiting_priority", nullable = false)
    private int waitingPriority;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = AppointmentStatus.SCHEDULED;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
