package com.orthoflow.inventory.domain.repository;

import com.orthoflow.inventory.domain.model.MovementType;
import com.orthoflow.inventory.domain.model.StockMovement;
import com.orthoflow.inventory.domain.model.StockMovementFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockMovementRepository {
    StockMovement save(StockMovement stockMovement);
    Optional<StockMovement> findById(UUID id);
    /** Returns all movements — backward-compatible, unfiltered. */
    List<StockMovement> findAll();
    /** Returns movements matching the given filter criteria with server-side sort. */
    List<StockMovement> findAll(StockMovementFilter filter);
    /** Same as above, bounded to one page — used by the audit ledger UI (audit II.8). */
    Page<StockMovement> findAll(StockMovementFilter filter, Pageable pageable);
    List<StockMovement> findByStockItemId(UUID stockItemId);
    /** Movements of a given type within a time window — used by analytics (monthly cost, top consumed). */
    List<StockMovement> findByMovementTypeAndCreatedAtBetween(MovementType movementType, OffsetDateTime start, OffsetDateTime end);
    /** What the items that went out in the window cost at their purchase price, summed by the database. */
    java.math.BigDecimal costOfMovementsBetween(MovementType movementType, OffsetDateTime start, OffsetDateTime end);

    record ItemTotal(UUID stockItemId, String name, java.math.BigDecimal purchasePrice, java.math.BigDecimal quantity) {
    }

    /** The items moved most in the window, largest quantity first. */
    List<ItemTotal> topItemsBetween(MovementType movementType, OffsetDateTime start, OffsetDateTime end, int limit);

    void deleteById(UUID id);
}
