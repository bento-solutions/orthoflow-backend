package com.orthoflow.treatment.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.TenantId;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;
import com.orthoflow.inventory.domain.model.StockItem;

@Entity
@Table(name = "treatment_consumables")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentConsumable {

    @EmbeddedId
    @Builder.Default
    private TreatmentConsumableId id = new TreatmentConsumableId();

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("treatmentId")
    @JoinColumn(name = "treatment_id")
    @JsonIgnore
    private Treatment treatment;

    @ManyToOne(fetch = FetchType.EAGER)
    @MapsId("stockItemId")
    @JoinColumn(name = "stock_item_id")
    private StockItem stockItem;

    @Column(name = "quantity_used", nullable = false)
    private BigDecimal quantityUsed;

    /**
     * The band around {@link #quantityUsed} inside which a difference is simply how the
     * work went, and is never flagged. A syringe is not drawn to the microlitre; a
     * bracket either was or was not placed, which is why countable items default to 0.
     */
    @Column(name = "variance_allowance_pct", nullable = false)
    @Builder.Default
    private BigDecimal varianceAllowancePct = new BigDecimal("10.00");

    /** The largest quantity that still counts as normal for this line. */
    public BigDecimal toleratedCeiling() {
        BigDecimal pct = varianceAllowancePct == null ? BigDecimal.ZERO : varianceAllowancePct;
        return quantityUsed.add(quantityUsed.multiply(pct).movePointLeft(2));
    }

    @Column(name = "is_optional", nullable = false)
    @Builder.Default
    private boolean optional = false;

    @Column(columnDefinition = "TEXT")
    private String notes;

    public void setTreatment(Treatment treatment) {
        this.treatment = treatment;
        if (treatment != null) {
            this.id.setTreatmentId(treatment.getId());
        }
    }

    public void setStockItem(StockItem stockItem) {
        this.stockItem = stockItem;
        if (stockItem != null) {
            this.id.setStockItemId(stockItem.getId());
        }
    }
}
