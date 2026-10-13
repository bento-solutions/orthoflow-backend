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

    /** The wording a clinic likes to read — "box of 100 pairs". Display only. */
    @Column(nullable = false)
    private String unit;

    /** What a quantity of this item is actually counted in. This is the one the system reasons about. */
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_of_measure", nullable = false, length = 20)
    @Builder.Default
    private UnitOfMeasure unitOfMeasure = UnitOfMeasure.PIECE;

    /**
     * How many applications one unit yields before it is spent — five polishes from one
     * disc. 1 means the ordinary case: using it consumes it.
     */
    @Column(name = "uses_per_unit", nullable = false)
    @Builder.Default
    private int usesPerUnit = 1;

    /**
     * How far through the unit currently in service we are, always below
     * {@link #usesPerUnit}: reaching it is what takes a unit off the shelf and returns
     * this to zero.
     */
    @Column(name = "uses_on_current_unit", nullable = false)
    @Builder.Default
    private int usesOnCurrentUnit = 0;

    /**
     * Whether stock of this item arrives in a container worth counting.
     *
     * <p>A 1000 ml bottle of lidocaine does; a cheek retractor, whose pack is itself one
     * unit, does not — "1 × 1 pc" is noise.
     */
    public boolean packaged() {
        return unitSize != null && unitSize.compareTo(BigDecimal.ONE) > 0;
    }

    /**
     * Whole containers the current stock amounts to — the "3" in "3 × 1000 ml".
     *
     * <p>Nobody can count a shelf in millilitres. 3000 ml is three bottles, and that is
     * the number a person standing in the storeroom can actually check.
     */
    public int packCount() {
        if (!packaged() || currentStock == null) {
            return 0;
        }
        return currentStock.divideToIntegralValue(unitSize).intValue();
    }

    /**
     * What is left over beyond the whole containers — the open bottle. Zero when the
     * stock divides evenly.
     */
    public BigDecimal packRemainder() {
        if (!packaged() || currentStock == null) {
            return BigDecimal.ZERO;
        }
        return currentStock.remainder(unitSize);
    }

    /** Whether this item survives more than one use. */
    public boolean multiUse() {
        return usesPerUnit > 1;
    }

    /** Uses left on the unit in service — the "2" in "3/5". */
    public int usesRemainingOnCurrentUnit() {
        return Math.max(0, usesPerUnit - usesOnCurrentUnit);
    }

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
