package com.orthoflow.treatment.domain.model;

import org.hibernate.annotations.TenantId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;
import com.orthoflow.inventory.domain.model.StockItem;

@Entity
@Table(name = "treatment_invoice_consumables")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentInvoiceConsumable {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "treatment_invoice_id", nullable = false)
    @JsonIgnore
    private TreatmentInvoice treatmentInvoice;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    @Column(name = "default_quantity", nullable = false)
    private BigDecimal defaultQuantity;

    @Column(name = "actual_quantity", nullable = false)
    private BigDecimal actualQuantity;

    @Column(name = "price_per_unit", nullable = false, precision = 12, scale = 4)
    private BigDecimal pricePerUnit;

    @Column(name = "total_cost", nullable = false)
    private BigDecimal totalCost;

    @Column(name = "is_modified", nullable = false)
    @Builder.Default
    private boolean modified = false;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (pricePerUnit == null && stockItem != null) {
            pricePerUnit = stockItem.getPricePerUse();
        }
        calculateTotalCost();
        checkModified();
    }

    @PreUpdate
    public void preUpdate() {
        calculateTotalCost();
        checkModified();
    }

    public void calculateTotalCost() {
        if (actualQuantity != null && pricePerUnit != null) {
            this.totalCost = actualQuantity.multiply(pricePerUnit);
        }
    }

    public void checkModified() {
        if (defaultQuantity != null && actualQuantity != null) {
            this.modified = defaultQuantity.compareTo(actualQuantity) != 0;
        }
    }
}
