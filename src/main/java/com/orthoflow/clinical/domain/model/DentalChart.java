package com.orthoflow.clinical.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "dental_charts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DentalChart {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "patient_id", nullable = false, unique = true)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "chart_type", nullable = false, length = 10)
    private ChartType chartType;

    @Builder.Default
    @OneToMany(mappedBy = "chart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ToothState> toothStates = new ArrayList<>();

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
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
