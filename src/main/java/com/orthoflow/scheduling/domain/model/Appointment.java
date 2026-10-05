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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppointmentStatus status;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "appliance_step")
    private Integer applianceStep;

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
