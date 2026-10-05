package com.orthoflow.treatment.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deleting a treatment archives it (deleted_at/deleted_by) rather than
 * removing the row — clinical treatment history carries retention
 * obligations (audit II.15).
 */
@Entity
@Table(name = "patient_treatments")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientTreatment {

    @Id
    private UUID id;

    @Version
    @Column(name = "version")
    private Long version;

    // A plain UUID rather than a @ManyToOne Patient — see audit I.2 /
    // docs/adr/0001-patient-in-billing.md. stock reads patient data (where
    // it needs to at all) through PatientLookup.
    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "treatment_id", nullable = false)
    private Treatment treatment;

    @Column(nullable = false)
    private String teeth; // Comma separated tooth IDs e.g. "11,12"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PatientTreatmentStatus status = PatientTreatmentStatus.PLANNED;

    @Column(nullable = false)
    @Builder.Default
    private int progress = 0;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "doctor_name")
    private String doctorName;

    /** The real record behind {@code doctorName}; the name is kept in step for screens that predate it. */
    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "stock_movements_generated", nullable = false)
    @Builder.Default
    private boolean stockMovementsGenerated = false;

    @OneToMany(mappedBy = "patientTreatment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PatientTreatmentConsumable> consumables = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "deleted_by")
    private UUID deletedBy;

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
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public void addConsumable(PatientTreatmentConsumable consumable) {
        consumables.add(consumable);
        consumable.setPatientTreatment(this);
    }
}
