package com.orthoflow.inventory.application.service;

import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.inventory.domain.repository.StockMovementRepository;
import com.orthoflow.inventory.domain.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Protects the stock-ledger invariants audit II.3/II.6 called out: a
 * movement can never be attributed to a fabricated/null actor, and stock can
 * never go negative regardless of movement type.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private StockItemRepository stockItemRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private SupplierRepository supplierRepository;

    private StockService stockService;

    @BeforeEach
    void setUp() {
        stockService = new StockService(stockItemRepository, stockMovementRepository, supplierRepository);
    }

    private StockItem itemWithStock(BigDecimal currentStock) {
        return StockItem.builder()
                .id(UUID.randomUUID())
                .name("Brackets")
                .sku("BR-001")
                .currentStock(currentStock)
                .minimumStock(BigDecimal.TEN)
                .build();
    }

    @Test
    void recordMovement_outboundExceedingStock_isRejectedNotAllowedNegative() {
        StockItem item = itemWithStock(BigDecimal.valueOf(2));
        when(stockItemRepository.findByIdForUpdate(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> stockService.recordMovement(
                item.getId(), MovementType.OUT, BigDecimal.valueOf(10),
                SourceType.MANUAL_ADJUSTMENT, item.getId(), "REF", "notes", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient stock");

        verify(stockMovementRepository, never()).save(any());
        assertThat(item.getCurrentStock()).isEqualByComparingTo("2");
    }

    @Test
    void recordMovement_inboundIncreasesStock() {
        StockItem item = itemWithStock(BigDecimal.valueOf(5));
        when(stockItemRepository.findByIdForUpdate(item.getId())).thenReturn(Optional.of(item));
        when(stockItemRepository.save(any(StockItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));

        StockMovement movement = stockService.recordMovement(
                item.getId(), MovementType.IN, BigDecimal.valueOf(20),
                SourceType.DELIVERY_NOTE, item.getId(), "PO-1", "restock", UUID.randomUUID());

        assertThat(item.getCurrentStock()).isEqualByComparingTo("25");
        assertThat(movement.getQuantityBefore()).isEqualByComparingTo("5");
        assertThat(movement.getQuantityAfter()).isEqualByComparingTo("25");
    }

    @Test
    void recordMovement_adjustmentBelowZero_isRejected() {
        StockItem item = itemWithStock(BigDecimal.valueOf(3));
        when(stockItemRepository.findByIdForUpdate(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> stockService.recordMovement(
                item.getId(), MovementType.ADJUSTMENT, BigDecimal.valueOf(-5),
                SourceType.MANUAL_ADJUSTMENT, item.getId(), "REF", "correction", UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative stock");
    }

    @Test
    void recordMovement_everyMovementRequiresANonNullActor() {
        // audit II.3: a fabricated/random UUID (or null) as createdBy made
        // the audit trail worthless. The service must refuse to record a
        // movement without a real actor rather than silently inventing one.
        StockItem item = itemWithStock(BigDecimal.valueOf(5));
        when(stockItemRepository.findByIdForUpdate(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> stockService.recordMovement(
                item.getId(), MovementType.IN, BigDecimal.valueOf(1),
                SourceType.MANUAL_ADJUSTMENT, item.getId(), "REF", "notes", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recordMovement_createsAnAuditTrailEntryWithBeforeAndAfterQuantities() {
        StockItem item = itemWithStock(BigDecimal.valueOf(10));
        when(stockItemRepository.findByIdForUpdate(item.getId())).thenReturn(Optional.of(item));
        when(stockItemRepository.save(any(StockItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID actor = UUID.randomUUID();

        StockMovement movement = stockService.recordMovement(
                item.getId(), MovementType.OUT, BigDecimal.valueOf(4),
                SourceType.PATIENT_TREATMENT, item.getId(), "PT-1", "consumed", actor);

        assertThat(movement.getCreatedBy()).isEqualTo(actor);
        assertThat(movement.getQuantityBefore()).isEqualByComparingTo("10");
        assertThat(movement.getQuantityAfter()).isEqualByComparingTo("6");
        assertThat(movement.getSourceReference()).isEqualTo("PT-1");
    }

    @Test
    void computePricePerUse_neverProducesANegativeValue() {
        StockItem item = StockItem.builder()
                .id(UUID.randomUUID())
                .purchasePrice(BigDecimal.valueOf(100))
                .unitSize(BigDecimal.ZERO) // guarded division-by-zero case (audit II.1)
                .build();

        item.computePricePerUse();

        assertThat(item.getPricePerUse()).isNotNull();
        assertThat(item.getPricePerUse().signum()).isGreaterThanOrEqualTo(0);
    }

    // ── Materials that survive more than one use ──

    private StockItem disc(int usesPerUnit, int usesSoFar, String stock) {
        return StockItem.builder()
                .id(UUID.randomUUID())
                .name("Polishing disc")
                .sku("POL-001")
                .unitOfMeasure(UnitOfMeasure.USE)
                .usesPerUnit(usesPerUnit)
                .usesOnCurrentUnit(usesSoFar)
                .currentStock(new BigDecimal(stock))
                .minimumStock(BigDecimal.ZERO)
                .build();
    }

    private void expectLockAndSaves(StockItem item) {
        when(stockItemRepository.findByIdForUpdate(item.getId())).thenReturn(Optional.of(item));
        lenient().when(stockItemRepository.save(any(StockItem.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void consumeUses_shortOfTheRollover_movesTheCounterAndTakesNothingOffTheShelf() {
        StockItem item = disc(5, 0, "3");
        expectLockAndSaves(item);

        for (int i = 1; i <= 4; i++) {
            stockService.consumeUses(item.getId(), BigDecimal.ONE,
                    SourceType.PATIENT_TREATMENT, item.getId(), "REF", null, UUID.randomUUID());
        }

        assertThat(item.getUsesOnCurrentUnit()).isEqualTo(4);
        assertThat(item.getCurrentStock()).isEqualByComparingTo("3");
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void consumeUses_atTheRollover_deductsOneUnitAndResetsTheCounter() {
        StockItem item = disc(5, 4, "3");
        expectLockAndSaves(item);

        stockService.consumeUses(item.getId(), BigDecimal.ONE,
                SourceType.PATIENT_TREATMENT, item.getId(), "REF", null, UUID.randomUUID());

        assertThat(item.getUsesOnCurrentUnit()).isZero();
        assertThat(item.getCurrentStock()).isEqualByComparingTo("2");
        verify(stockMovementRepository).save(any(StockMovement.class));
    }

    @Test
    void consumeUses_severalAtOnce_deductsEveryWholeUnitCrossed() {
        StockItem item = disc(5, 3, "4");
        expectLockAndSaves(item);

        // 3 already + 7 = 10 -> two units spent, nothing left over.
        stockService.consumeUses(item.getId(), BigDecimal.valueOf(7),
                SourceType.PATIENT_TREATMENT, item.getId(), "REF", null, UUID.randomUUID());

        assertThat(item.getUsesOnCurrentUnit()).isZero();
        assertThat(item.getCurrentStock()).isEqualByComparingTo("2");
    }

    @Test
    void restoreUses_undoingPastARollover_givesBackTheUnitAndTheCounter() {
        StockItem item = disc(5, 0, "2");
        expectLockAndSaves(item);

        stockService.restoreUses(item.getId(), BigDecimal.ONE,
                SourceType.PATIENT_TREATMENT, item.getId(), "REF", null, UUID.randomUUID());

        // Exactly reverses the use that had just spent a unit.
        assertThat(item.getUsesOnCurrentUnit()).isEqualTo(4);
        assertThat(item.getCurrentStock()).isEqualByComparingTo("3");
    }

    @Test
    void consumeUses_singleUseItem_behavesExactlyAsBefore() {
        StockItem item = disc(1, 0, "5");
        expectLockAndSaves(item);

        stockService.consumeUses(item.getId(), BigDecimal.valueOf(2),
                SourceType.PATIENT_TREATMENT, item.getId(), "REF", null, UUID.randomUUID());

        assertThat(item.getCurrentStock()).isEqualByComparingTo("3");
        assertThat(item.getUsesOnCurrentUnit()).isZero();
    }

    @Test
    void consumeUses_fractionOfAUse_isRejected() {
        StockItem item = disc(5, 0, "3");
        when(stockItemRepository.findByIdForUpdate(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> stockService.consumeUses(item.getId(), new BigDecimal("0.5"),
                SourceType.PATIENT_TREATMENT, item.getId(), "REF", null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole uses");

        assertThat(item.getUsesOnCurrentUnit()).isZero();
    }

    @Test
    void consumeUses_rollingOverWithAnEmptyShelf_isRefusedAndLeavesTheCounterAlone() {
        StockItem item = disc(5, 4, "0");
        expectLockAndSaves(item);

        assertThatThrownBy(() -> stockService.consumeUses(item.getId(), BigDecimal.ONE,
                SourceType.PATIENT_TREATMENT, item.getId(), "REF", null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient stock");

        verify(stockMovementRepository, never()).save(any());
    }

    // ── Counting the shelf in containers, not in millilitres ──

    private StockItem packed(String stock, String packSize) {
        return StockItem.builder()
                .id(UUID.randomUUID()).name("Lidocaine 2%").sku("ANS-LID")
                .unitOfMeasure(UnitOfMeasure.MILLILITRE)
                .currentStock(new BigDecimal(stock))
                .unitSize(new BigDecimal(packSize))
                .minimumStock(BigDecimal.ZERO)
                .build();
    }

    @Test
    void packCount_wholeContainers() {
        StockItem item = packed("3000", "1000");
        assertThat(item.packaged()).isTrue();
        assertThat(item.packCount()).isEqualTo(3);
        assertThat(item.packRemainder()).isEqualByComparingTo("0");
    }

    @Test
    void packCount_leavesTheOpenBottleAsARemainder() {
        StockItem item = packed("3050", "1000");
        assertThat(item.packCount()).isEqualTo(3);
        assertThat(item.packRemainder()).isEqualByComparingTo("50");
    }

    @Test
    void packCount_itemCountedOneToOneIsNotPackaged() {
        // "1 x 1 pc" is noise, so an item whose pack is itself one unit reports nothing.
        StockItem item = packed("30", "1");
        assertThat(item.packaged()).isFalse();
        assertThat(item.packCount()).isZero();
    }

    @Test
    void packCount_lessThanOneContainer() {
        StockItem item = packed("400", "1000");
        assertThat(item.packCount()).isZero();
        assertThat(item.packRemainder()).isEqualByComparingTo("400");
    }

    @Test
    void packCount_missingValuesDoNotThrow() {
        StockItem noSize = StockItem.builder().id(UUID.randomUUID()).currentStock(BigDecimal.TEN).build();
        assertThat(noSize.packaged()).isFalse();
        assertThat(noSize.packCount()).isZero();

        StockItem noStock = StockItem.builder().id(UUID.randomUUID()).unitSize(new BigDecimal("50")).build();
        assertThat(noStock.packCount()).isZero();
        assertThat(noStock.packRemainder()).isEqualByComparingTo("0");
    }
}
