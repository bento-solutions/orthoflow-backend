package com.orthoflow.inventory.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.inventory.application.dto.StockItemRequest;
import com.orthoflow.inventory.application.dto.SupplierRequest;
import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.inventory.domain.repository.StockMovementRepository;
import com.orthoflow.inventory.domain.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.orthoflow.inventory.domain.model.MovementType;
import com.orthoflow.inventory.domain.model.SourceType;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.inventory.domain.model.StockItemFilter;
import com.orthoflow.inventory.domain.model.StockMovement;
import com.orthoflow.inventory.domain.model.StockMovementFilter;
import com.orthoflow.inventory.domain.model.Supplier;

// Filter imports — domain value objects (DIP: service depends on domain, not infrastructure)

@Service
@RequiredArgsConstructor
@Slf4j
public class StockService {

    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SupplierRepository supplierRepository;

    public List<StockItem> getAllStockItems() {
        return stockItemRepository.findAll();
    }

    /**
     * Returns stock items matching the given filter (search, category) with server-side sort.
     * Delegates directly to the repository — service stays thin.
     */
    public List<StockItem> getStockItems(StockItemFilter filter) {
        return stockItemRepository.findAll(filter);
    }

    public Optional<StockItem> getStockItemById(UUID id) {
        return stockItemRepository.findById(id);
    }

    public Optional<StockItem> getStockItemBySku(String sku) {
        return stockItemRepository.findBySku(sku);
    }

    /** Active items whose currentStock has fallen to or below minimumStock. */
    public List<StockItem> getLowStockItems() {
        return stockItemRepository.findLowStock();
    }

    /** Active items with at least one delivery-note line expiring within the next {@code daysAhead} days. */
    public List<StockItem> getExpiringItems(int daysAhead) {
        java.time.LocalDate today = java.time.LocalDate.now();
        return stockItemRepository.findExpiring(today, today.plusDays(daysAhead));
    }

    /**
     * Creates a new stock item. `currentStock` always starts at zero and can
     * only move through recordMovement — a client cannot set it directly
     * (see audit V.6 / I.5).
     */
    @Transactional
    public StockItem createStockItem(StockItemRequest request, UUID createdBy) {
        StockItem item = StockItem.builder()
                .name(request.getName())
                .sku(request.getSku())
                .category(request.getCategory())
                .unit(request.getUnit())
                .unitOfMeasure(request.getUnitOfMeasure() == null ? UnitOfMeasure.PIECE : request.getUnitOfMeasure())
                .usesPerUnit(request.getUsesPerUnit() == null ? 1 : request.getUsesPerUnit())
                .unitSize(request.getUnitSize())
                .unitLabel(request.getUnitLabel())
                .purchasePrice(request.getPurchasePrice())
                .minimumStock(request.getMinimumStock())
                .currentStock(BigDecimal.ZERO)
                .svgIcon(request.getSvgIcon())
                .active(request.isActive())
                .decimalSupported(request.isDecimalSupported())
                .notes(request.getNotes())
                .build();
        applySupplier(item, request.getSupplierId());
        item.computePricePerUse();
        StockItem saved = stockItemRepository.save(item);

        if (request.getInitialStock() != null && request.getInitialStock().compareTo(BigDecimal.ZERO) > 0) {
            recordMovement(saved.getId(), MovementType.IN, request.getInitialStock(),
                    SourceType.MANUAL_ADJUSTMENT, saved.getId(), "OPENING_STOCK",
                    "Opening stock recorded at item creation", createdBy);
            return stockItemRepository.findById(saved.getId()).orElse(saved);
        }
        return saved;
    }

    /**
     * Updates a stock item's catalog fields. `currentStock` is deliberately
     * left untouched — it can only move through recordMovement.
     */
    @Transactional
    public StockItem updateStockItem(UUID id, StockItemRequest request) {
        StockItem item = stockItemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Stock item not found: " + id));

        item.setName(request.getName());
        item.setSku(request.getSku());
        item.setCategory(request.getCategory());
        item.setUnit(request.getUnit());
        if (request.getUnitOfMeasure() != null) {
            item.setUnitOfMeasure(request.getUnitOfMeasure());
        }
        applyUsesPerUnit(item, request.getUsesPerUnit());
        item.setUnitSize(request.getUnitSize());
        item.setUnitLabel(request.getUnitLabel());
        item.setPurchasePrice(request.getPurchasePrice());
        item.setMinimumStock(request.getMinimumStock());
        item.setSvgIcon(request.getSvgIcon());
        item.setActive(request.isActive());
        item.setDecimalSupported(request.isDecimalSupported());
        item.setNotes(request.getNotes());
        applySupplier(item, request.getSupplierId());
        item.computePricePerUse();
        return stockItemRepository.save(item);
    }

    private void applySupplier(StockItem item, UUID supplierId) {
        if (supplierId != null) {
            Supplier supplier = supplierRepository.findById(supplierId)
                    .orElseThrow(() -> new NotFoundException("Supplier not found: " + supplierId));
            item.setSupplier(supplier);
        } else {
            item.setSupplier(null);
        }
    }

    @Transactional
    public void deleteStockItem(UUID id) {
        stockItemRepository.deleteById(id);
    }

    // ---------------- Supplier Management ----------------
    public List<Supplier> getAllSuppliers() {
        return supplierRepository.findAll();
    }

    public Optional<Supplier> getSupplierById(UUID id) {
        return supplierRepository.findById(id);
    }

    /**
     * Creates or updates a supplier depending on whether {@code id} is
     * present. `id`/`createdAt`/`updatedAt` are server-owned — binding the
     * entity directly (the previous behaviour) let a client set those, and
     * skip validation on `name`/`email` entirely (see audit I.5 / V.6).
     */
    @Transactional
    public Supplier saveSupplier(SupplierRequest request, UUID id) {
        Supplier supplier;
        if (id != null) {
            supplier = supplierRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Supplier not found: " + id));
        } else {
            supplier = new Supplier();
        }

        supplier.setName(request.getName());
        supplier.setContactName(request.getContactName());
        supplier.setEmail(request.getEmail());
        supplier.setPhone(request.getPhone());
        supplier.setAddress(request.getAddress());
        if (request.getKind() != null) {
            supplier.setKind(com.orthoflow.inventory.domain.model.SupplierKind.valueOf(request.getKind()));
        }
        supplier.setActive(request.isActive());
        return supplierRepository.save(supplier);
    }

    @Transactional
    public void deleteSupplier(UUID id) {
        supplierRepository.deleteById(id);
    }

    /**
     * Changes how many uses a unit yields, keeping the running counter inside it.
     *
     * <p>Lowering the capacity below where the unit in service already is would leave a
     * counter that can never roll over, so the part-used unit is treated as spent: the
     * counter goes back to zero. It does not deduct stock — nothing was used that was
     * not already recorded, and silently removing a unit during an edit to a catalogue
     * entry would be a surprising place to find a stock movement.
     */
    private static void applyUsesPerUnit(StockItem item, Integer requested) {
        if (requested == null || requested == item.getUsesPerUnit()) {
            return;
        }
        item.setUsesPerUnit(Math.max(1, requested));
        if (item.getUsesOnCurrentUnit() >= item.getUsesPerUnit()) {
            item.setUsesOnCurrentUnit(0);
        }
    }

    // ---------------- Uses, for materials that survive more than one ----------------

    /**
     * Records {@code uses} applications of an item and takes whole units off the shelf
     * only when they are actually spent.
     *
     * <p>For an ordinary item ({@code usesPerUnit == 1}) this is exactly the old
     * behaviour: one use, one unit out. For a disc good for five polishes, the first
     * four move a counter and nothing leaves the shelf; the fifth rolls the counter over
     * and deducts one unit.
     *
     * <p>The partially-used unit is still stock — three discs with one of them two
     * polishes in is {@code currentStock = 3, usesOnCurrentUnit = 2}. That is why
     * consuming a use with nothing on the shelf is refused: there would be no disc to
     * pick up.
     *
     * <p>Runs inside the same lock and transaction as the movement it may trigger, so a
     * counter never advances without the deduction it implies, and an insufficient-stock
     * refusal rolls the counter back with it.
     */
    @Transactional
    public void consumeUses(UUID stockItemId, BigDecimal uses, SourceType sourceType, UUID sourceId,
                            String sourceReference, String notes, UUID createdBy) {
        applyUses(stockItemId, uses, true, sourceType, sourceId, sourceReference, notes, createdBy);
    }

    /** Reverses {@link #consumeUses} — the same arithmetic run backwards. */
    @Transactional
    public void restoreUses(UUID stockItemId, BigDecimal uses, SourceType sourceType, UUID sourceId,
                            String sourceReference, String notes, UUID createdBy) {
        applyUses(stockItemId, uses, false, sourceType, sourceId, sourceReference, notes, createdBy);
    }

    private void applyUses(UUID stockItemId, BigDecimal uses, boolean consuming, SourceType sourceType,
                           UUID sourceId, String sourceReference, String notes, UUID createdBy) {
        StockItem item = stockItemRepository.findByIdForUpdate(stockItemId)
                .orElseThrow(() -> new IllegalArgumentException("Stock item not found: " + stockItemId));

        if (!item.multiUse()) {
            recordMovement(stockItemId, consuming ? MovementType.OUT : MovementType.RETURN, uses,
                    sourceType, sourceId, sourceReference, notes, createdBy);
            return;
        }

        int count = wholeUses(uses, item);
        if (count == 0) {
            return;
        }
        int perUnit = item.getUsesPerUnit();
        int running = item.getUsesOnCurrentUnit() + (consuming ? count : -count);
        // floorDiv/floorMod rather than / and %: running goes negative when a restore
        // undoes past a rollover, and only the floor versions put the counter back where
        // it was instead of landing on a negative remainder.
        int units = Math.floorDiv(running, perUnit);
        item.setUsesOnCurrentUnit(Math.floorMod(running, perUnit));
        stockItemRepository.save(item);

        if (units != 0) {
            recordMovement(stockItemId, units > 0 ? MovementType.OUT : MovementType.RETURN,
                    BigDecimal.valueOf(Math.abs(units)), sourceType, sourceId, sourceReference, notes, createdBy);
        }
    }

    /** A use is indivisible: there is no half a polish. */
    private static int wholeUses(BigDecimal uses, StockItem item) {
        if (uses == null) {
            return 0;
        }
        if (uses.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "\"" + item.getName() + "\" is counted in whole uses (" + item.getUsesPerUnit()
                            + " per unit), so " + uses.toPlainString() + " is not a quantity it can take");
        }
        return uses.intValueExact();
    }

    // ---------------- Stock Movement Logging & Logic ----------------
    @Transactional
    public StockMovement recordMovement(UUID stockItemId, MovementType type, BigDecimal quantity,
                                        SourceType sourceType, UUID sourceId, String sourceReference,
                                        String notes, UUID createdBy) {

        // Pessimistic write lock: two concurrent OUT movements on the same
        // item must not both read the same currentStock, both pass the
        // insufficient-stock check below, and both deduct — that race is
        // what let stock go negative under load (audit II.6). This blocks
        // the second transaction until the first commits or rolls back.
        StockItem stockItem = stockItemRepository.findByIdForUpdate(stockItemId)
                .orElseThrow(() -> new IllegalArgumentException("Stock item not found: " + stockItemId));

        BigDecimal quantityBefore = stockItem.getCurrentStock() != null ? stockItem.getCurrentStock() : BigDecimal.ZERO;
        BigDecimal quantityAfter;

        switch (type) {
            case IN:
            case RETURN:
                quantityAfter = quantityBefore.add(quantity);
                break;
            case OUT:
            case WRITE_OFF:
                quantityAfter = quantityBefore.subtract(quantity);
                if (quantityAfter.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException(
                            "Insufficient stock for item: " + stockItem.getName() +
                                    " (Available: " + quantityBefore + ", Required: " + quantity + ")");
                }
                break;
            case ADJUSTMENT:
                // For manual adjustment, quantity represents the signed delta (positive to add, negative to subtract)
                quantityAfter = quantityBefore.add(quantity);
                if (quantityAfter.compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalArgumentException(
                            "Adjustment would result in negative stock for item: " + stockItem.getName());
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported movement type: " + type);
        }

        stockItem.setCurrentStock(quantityAfter);
        stockItemRepository.save(stockItem);

        StockMovement movement = StockMovement.builder()
                .stockItem(stockItem)
                .movementType(type)
                .quantity(quantity)
                .quantityBefore(quantityBefore)
                .quantityAfter(quantityAfter)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .sourceReference(sourceReference)
                .notes(notes)
                .createdBy(java.util.Objects.requireNonNull(createdBy, "createdBy is required for a stock movement"))
                .build();

        StockMovement savedMovement = stockMovementRepository.save(movement);

        // Low stock alerts
        if (quantityAfter.compareTo(stockItem.getMinimumStock()) < 0) {
            log.warn("ALERT: Stock item '{}' (SKU: {}) is below minimum stock! Current: {}, Minimum: {}",
                    stockItem.getName(), stockItem.getSku(), quantityAfter, stockItem.getMinimumStock());
        }

        return savedMovement;
    }

    public List<StockMovement> getMovementsByStockItem(UUID stockItemId) {
        return stockMovementRepository.findByStockItemId(stockItemId);
    }

    public List<StockMovement> getAllMovements() {
        return stockMovementRepository.findAll();
    }

    /**
     * Returns stock movements matching the given filter (search, type) with server-side sort.
     */
    public List<StockMovement> getMovements(StockMovementFilter filter) {
        return stockMovementRepository.findAll(filter);
    }

    public org.springframework.data.domain.Page<StockMovement> getMovements(
            StockMovementFilter filter, org.springframework.data.domain.Pageable pageable) {
        return stockMovementRepository.findAll(filter, pageable);
    }
}
