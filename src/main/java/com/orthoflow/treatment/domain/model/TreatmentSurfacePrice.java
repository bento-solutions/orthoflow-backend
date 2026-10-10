package com.orthoflow.treatment.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.util.UUID;

/** What a treatment costs when it covers {@code surfaceCount} faces of the tooth. */
@Entity
@Table(name = "treatment_surface_prices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentSurfacePrice {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "treatment_id", nullable = false)
    @JsonIgnore
    private Treatment treatment;

    @Column(name = "surface_count", nullable = false)
    private int surfaceCount;

    @Column(nullable = false)
    private BigDecimal price;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }
}
