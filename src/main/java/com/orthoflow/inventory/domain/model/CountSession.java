package com.orthoflow.inventory.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "count_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountSession {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "session_number", unique = true, nullable = false)
    private String sessionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CountSessionStatus status = CountSessionStatus.OPEN;

    @OneToMany(mappedBy = "countSession", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CountSessionLine> lines = new ArrayList<>();

    @Column(name = "snapshot_date")
    private OffsetDateTime snapshotDate;

    @Column(name = "count_date")
    private LocalDate countDate;

    @Column(name = "validated_date")
    private OffsetDateTime validatedDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "total_quantity_variance", nullable = false)
    @Builder.Default
    private BigDecimal totalQuantityVariance = BigDecimal.ZERO;

    @Column(name = "total_cost_variance", nullable = false)
    @Builder.Default
    private BigDecimal totalCostVariance = BigDecimal.ZERO;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "validated_by")
    private UUID validatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (snapshotDate == null) {
            snapshotDate = OffsetDateTime.now();
        }
        if (countDate == null) {
            countDate = LocalDate.now();
        }
    }

    public void addLine(CountSessionLine line) {
        lines.add(line);
        line.setCountSession(this);
    }
}
