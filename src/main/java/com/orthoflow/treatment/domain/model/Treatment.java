package com.orthoflow.treatment.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "treatments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Treatment {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    private String category;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    /**
     * The act code and coefficient a mutual insurer reads on a care form. Left blank
     * until the clinic confirms the Moroccan NGAP coding for its acts: a wrong code
     * on a form is worse than none.
     */
    @Column(name = "act_code")
    private String actCode;

    @Column(name = "act_coefficient")
    private BigDecimal actCoefficient;

    @OneToMany(mappedBy = "treatment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TreatmentConsumable> consumables = new ArrayList<>();

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

    public void addConsumable(TreatmentConsumable consumable) {
        consumables.add(consumable);
        consumable.setTreatment(this);
    }
}
