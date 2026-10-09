package com.orthoflow.inventory.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "stock_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockItem {

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
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockCategory category;

    @Column(nullable = false)
    private String unit;

    @Column(name = "unit_size", nullable = false)
    private BigDecimal unitSize;

    @Column(name = "unit_label", nullable = false)
    private String unitLabel;

    @Column(name = "purchase_price", nullable = false)
    private BigDecimal purchasePrice;

    @Column(name = "price_per_use", nullable = false)
    private BigDecimal pricePerUse;

    @Column(name = "current_stock", nullable = false)
    @Builder.Default
    private BigDecimal currentStock = BigDecimal.ZERO;

    @Column(name = "minimum_stock", nullable = false)
    @Builder.Default
    private BigDecimal minimumStock = BigDecimal.ZERO;

    @Column(name = "svg_icon", columnDefinition = "TEXT")
    private String svgIcon;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "decimal_supported", nullable = false)
    @Builder.Default
    private boolean decimalSupported = false;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(columnDefinition = "TEXT")
    private String notes;

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
        computePricePerUse();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
        computePricePerUse();
    }

    public void computePricePerUse() {
        if (purchasePrice != null && unitSize != null && unitSize.compareTo(BigDecimal.ZERO) > 0) {
            this.pricePerUse = purchasePrice.divide(unitSize, 4, RoundingMode.HALF_UP);
        } else {
            this.pricePerUse = BigDecimal.ZERO;
        }
    }
}
