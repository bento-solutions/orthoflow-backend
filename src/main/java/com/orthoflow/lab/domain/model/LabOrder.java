package com.orthoflow.lab.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "lab_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabOrder {

    @Id
    private UUID id;

    @Version
    private Long version;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "lab_id", nullable = false)
    private UUID labId;

    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false)
    private LabItemType itemType;

    private String description;

    @Column(name = "sent_date")
    private LocalDate sentDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LabStatus status = LabStatus.SENT;

    @Column(nullable = false)
    private boolean urgent;

    private BigDecimal cost;

    @Column(name = "fitting_appointment_id")
    private UUID fittingAppointmentId;

    @Column(name = "received_date")
    private LocalDate receivedDate;

    @Column(name = "fitted_date")
    private LocalDate fittedDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

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
