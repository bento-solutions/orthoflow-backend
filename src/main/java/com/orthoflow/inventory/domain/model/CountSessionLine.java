package com.orthoflow.inventory.domain.model;

import org.hibernate.annotations.TenantId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "count_session_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountSessionLine {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "count_session_id", nullable = false)
    @JsonIgnore
    private CountSession countSession;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    @Column(name = "theoretical_quantity", nullable = false)
    private BigDecimal theoreticalQuantity;

    @Column(name = "physical_quantity")
    private BigDecimal physicalQuantity;

    @Column(name = "quantity_variance")
    private BigDecimal quantityVariance;

    @Column(name = "cost_variance")
    private BigDecimal costVariance;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
