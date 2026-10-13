package com.orthoflow.inventory.application.dto;

import com.orthoflow.inventory.domain.model.StockCategory;
import com.orthoflow.inventory.domain.model.UnitOfMeasure;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for creating/updating a stock item. Deliberately excludes
 * `currentStock`, `id`, `createdAt` — those are server-owned. Binding the
 * entity directly (the previous behaviour) let a client set `currentStock`
 * on a PUT, bypassing the stock-movement ledger entirely (see audit V.6).
 * Inventory levels can only change through StockService.recordMovement.
 */
@Getter
@Setter
public class StockItemRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String sku;

    @NotNull
    private StockCategory category;

    @NotBlank
    private String unit;

    /** What a quantity is counted in. Defaults to PIECE for a caller that does not say. */
    private UnitOfMeasure unitOfMeasure;

    /**
     * Applications one unit yields before it is spent — 5 for a disc good for five
     * polishes. Null or 1 is the ordinary case: using it consumes it.
     */
    @Min(1)
    @Max(1000)
    private Integer usesPerUnit;

    @NotNull
    @DecimalMin(value = "0.0001", message = "unitSize must be greater than zero")
    private BigDecimal unitSize;

    @NotBlank
    private String unitLabel;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal purchasePrice;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal minimumStock;

    private String svgIcon;

    private boolean active = true;

    private boolean decimalSupported = false;

    private UUID supplierId;

    private String notes;

    /**
     * Opening stock quantity, applied only on creation and only as a
     * recorded MANUAL_ADJUSTMENT movement (never written to `currentStock`
     * directly) so the ledger stays the single source of truth for
     * inventory level (audit V.6). Ignored on update — use the dedicated
     * adjustment endpoint to correct an existing item's stock.
     */
    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal initialStock;
}
