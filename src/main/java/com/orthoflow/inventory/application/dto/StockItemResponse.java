package com.orthoflow.inventory.application.dto;

import com.orthoflow.inventory.domain.model.StockCategory;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.inventory.domain.model.UnitOfMeasure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response shape for StockItem — closes the "controllers return JPA
 * entities directly" half of audit I.5 that request DTOs (StockItemRequest)
 * didn't cover. Field names/types match frontend/src/app/core/models/
 * stock.model.ts StockItem exactly so this is a pure serialization-boundary
 * change, not a contract change.
 */
public record StockItemResponse(
        UUID id,
        String sku,
        String name,
        StockCategory category,
        String unit,
        String unitLabel,
        /** The unit the system reasons about; {@code unit} is only the wording. */
        UnitOfMeasure unitOfMeasure,
        String unitSymbol,
        /** Applications per unit; 1 means using it consumes it. */
        int usesPerUnit,
        /** How far through the unit in service — the "3" in "3/5". */
        int usesOnCurrentUnit,
        boolean decimalSupported,
        SupplierResponse supplier,
        BigDecimal currentStock,
        BigDecimal minimumStock,
        BigDecimal purchasePrice,
        BigDecimal unitSize,
        /**
         * How the stock breaks down into containers: 3000 ml of lidocaine is
         * {@code packCount = 3} of a 1000 ml bottle. Zero when the item is already
         * counted one-to-one, where "1 × 1 pc" would only be noise.
         */
        int packCount,
        /** The open container — what is left beyond the whole ones. */
        BigDecimal packRemainder,
        BigDecimal pricePerUse,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static StockItemResponse from(StockItem item) {
        if (item == null) return null;
        return new StockItemResponse(
                item.getId(),
                item.getSku(),
                item.getName(),
                item.getCategory(),
                item.getUnit(),
                item.getUnitLabel(),
                item.getUnitOfMeasure(),
                item.getUnitOfMeasure() == null ? null : item.getUnitOfMeasure().symbol(),
                item.getUsesPerUnit(),
                item.getUsesOnCurrentUnit(),
                item.isDecimalSupported(),
                SupplierResponse.from(item.getSupplier()),
                item.getCurrentStock(),
                item.getMinimumStock(),
                item.getPurchasePrice(),
                item.getUnitSize(),
                item.packCount(),
                item.packRemainder(),
                item.getPricePerUse(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
