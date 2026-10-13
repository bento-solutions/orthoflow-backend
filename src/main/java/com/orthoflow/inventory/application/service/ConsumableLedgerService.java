package com.orthoflow.inventory.application.service;

import com.orthoflow.inventory.application.port.ConsumableLedger;
import com.orthoflow.inventory.domain.model.MovementType;
import com.orthoflow.inventory.domain.model.SourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The only implementation of {@link ConsumableLedger} — every module outside
 * {@code inventory} depends on the port, never on this class or on
 * {@code StockService} directly.
 *
 * <p>Delegates straight to {@link StockService#recordMovement}, which already
 * holds the pessimistic write lock and the insufficient-stock guard (see its
 * own doc comment) — this class only translates the three business verbs into
 * the {@link MovementType} that method expects, so callers outside
 * {@code inventory} never need to know that enum exists.
 */
@Service
@RequiredArgsConstructor
public class ConsumableLedgerService implements ConsumableLedger {

    private final StockService stockService;

    @Override
    public void receive(UUID stockItemId, BigDecimal quantity, SourceType sourceType, UUID sourceId,
                         String sourceReference, String notes, UUID actorId) {
        stockService.recordMovement(stockItemId, MovementType.IN, quantity, sourceType, sourceId,
                sourceReference, notes, actorId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Goes through {@link StockService#consumeUses} rather than straight to a
     * movement, because for a material that survives several uses the quantity is a
     * number of uses, not of units: four polishes off a five-polish disc move a counter
     * and take nothing off the shelf. For everything else — {@code usesPerUnit == 1} —
     * that method is the same single OUT movement as before.
     */
    @Override
    public void consume(UUID stockItemId, BigDecimal quantity, SourceType sourceType, UUID sourceId,
                         String sourceReference, String notes, UUID actorId) {
        stockService.consumeUses(stockItemId, quantity, sourceType, sourceId, sourceReference, notes, actorId);
    }

    /** {@inheritDoc} Runs the use arithmetic backwards, so reopening a session gives back the use, not a unit. */
    @Override
    public void restore(UUID stockItemId, BigDecimal quantity, SourceType sourceType, UUID sourceId,
                         String sourceReference, String notes, UUID actorId) {
        stockService.restoreUses(stockItemId, quantity, sourceType, sourceId, sourceReference, notes, actorId);
    }
}
