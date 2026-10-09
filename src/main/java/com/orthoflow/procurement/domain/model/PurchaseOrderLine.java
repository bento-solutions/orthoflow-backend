package com.orthoflow.procurement.domain.model;

import org.hibernate.annotations.TenantId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;
import com.orthoflow.inventory.domain.model.StockItem;

@Entity
@Table(name = "purchase_order_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrderLine {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    @JsonIgnore
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    @Column(name = "quantity_ordered", nullable = false)
    private BigDecimal quantityOrdered;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "total_price", nullable = false)
    private BigDecimal totalPrice;

    @Column(name = "quantity_received", nullable = false)
    @Builder.Default
    private BigDecimal quantityReceived = BigDecimal.ZERO;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        calculateTotalPrice();
    }

    @PreUpdate
    public void preUpdate() {
        calculateTotalPrice();
    }

    public void calculateTotalPrice() {
        if (quantityOrdered != null && unitPrice != null) {
            this.totalPrice = quantityOrdered.multiply(unitPrice);
        }
    }
}
