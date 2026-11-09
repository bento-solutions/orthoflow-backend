package com.orthoflow.inventory.domain.repository;

import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.inventory.domain.model.StockItemFilter;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockItemRepository {
    StockItem save(StockItem stockItem);
    Optional<StockItem> findById(UUID id);
    /** Locks the row for the transaction — see StockItemJpaRepository#findByIdForUpdate. */
    Optional<StockItem> findByIdForUpdate(UUID id);
    Optional<StockItem> findBySku(String sku);
    /** Returns all items — backward-compatible, unfiltered. */
    List<StockItem> findAll();
    /** These items, in no particular order; ids of another clinic's items find nothing. */
    List<StockItem> findAllByIds(java.util.Collection<UUID> ids);

    /** Returns items matching the given filter criteria with server-side sort. */
    List<StockItem> findAll(StockItemFilter filter);
    /** Active items whose currentStock has fallen to or below minimumStock. */
    List<StockItem> findLowStock();
    /** Distinct active-item set with at least one delivery-note line expiring in [today, cutoff]. */
    List<StockItem> findExpiring(LocalDate today, LocalDate cutoff);
    /** Value at purchase price and the number at or below zero, of the active items, summed by the database. */
    record ActiveTotals(java.math.BigDecimal value, long outOfStock) {
    }

    ActiveTotals activeTotals();

    void deleteById(UUID id);
}
