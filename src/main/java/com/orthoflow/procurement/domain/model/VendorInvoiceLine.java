package com.orthoflow.procurement.domain.model;

import org.hibernate.annotations.TenantId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;
import com.orthoflow.inventory.domain.model.StockItem;

@Entity
@Table(name = "vendor_invoice_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorInvoiceLine {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_invoice_id", nullable = false)
    @JsonIgnore
    private VendorInvoice vendorInvoice;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    @Column(name = "quantity_invoiced", nullable = false)
    private BigDecimal quantityInvoiced;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "tax_rate")
    private BigDecimal taxRate;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        calculateLineTotal();
    }

    @PreUpdate
    public void preUpdate() {
        calculateLineTotal();
    }

    public void calculateLineTotal() {
        if (quantityInvoiced != null && unitPrice != null) {
            this.lineTotal = quantityInvoiced.multiply(unitPrice);
        }
    }
}
