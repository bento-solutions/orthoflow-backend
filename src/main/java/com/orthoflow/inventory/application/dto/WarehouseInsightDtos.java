package com.orthoflow.inventory.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Shapes for the two warehouse questions: where did the stock go, and what should we order. */
public final class WarehouseInsightDtos {

    private WarehouseInsightDtos() {
    }

    // ── Leakage ────────────────────────────────────────────────────────────

    /**
     * One doctor and one consumable over the period.
     *
     * <p>Read it left to right: the recipe expected {@code expected}; anything up to
     * {@code tolerated} is still normal; {@code actual} is what really left the shelf;
     * of the excess, {@code explained} was accounted for with a reason, and
     * {@code unexplained} is the part nobody has accounted for. Only the last column
     * is an accusation, and {@code unexplainedCost} is what it is worth.
     */
    public record LeakageLine(
            UUID practitionerId,
            String practitionerName,
            UUID stockItemId,
            String itemName,
            String unitOfMeasure,
            int treatments,
            BigDecimal expected,
            BigDecimal tolerated,
            BigDecimal actual,
            BigDecimal explained,
            /** Used again because the work was redone — legitimate, and kept out of the gap. */
            BigDecimal retreatment,
            BigDecimal unexplained,
            BigDecimal unexplainedCost) {
    }

    /** What a doctor looks like once their lines are added up. */
    public record LeakageByDoctor(
            UUID practitionerId,
            String practitionerName,
            int treatments,
            /** Visits that redid earlier work. Excluded from the gap; shown as a quality figure. */
            int retreatments,
            BigDecimal retreatmentCost,
            BigDecimal unexplainedCost,
            /** Unexplained as a share of what the recipes expected — comparable across doctors with different workloads. */
            BigDecimal unexplainedPctOfExpected,
            List<LeakageLine> lines) {
    }

    /** Stock that left with no treatment behind it: adjustments, write-offs, direct sales. */
    public record UnattributedConsumption(
            String itemName,
            String unitOfMeasure,
            String sourceType,
            BigDecimal quantity,
            BigDecimal cost) {
    }

    public record LeakageReport(
            LocalDate from,
            LocalDate to,
            BigDecimal totalUnexplainedCost,
            BigDecimal totalExplainedCost,
            BigDecimal totalRetreatmentCost,
            BigDecimal totalUnattributedCost,
            /**
             * What the report cannot see. Consumption is only attributable where a
             * movement was recorded against a treatment; this is the share that was,
             * and it is the first number to read, because a low one makes every other
             * figure here weaker than it looks.
             */
            BigDecimal attributionCoveragePct,
            List<LeakageByDoctor> byDoctor,
            List<UnattributedConsumption> unattributed) {
    }

    // ── Forecast ───────────────────────────────────────────────────────────

    /** One line of next period's suggested order. */
    public record OrderSuggestion(
            UUID stockItemId,
            String itemName,
            String unitOfMeasure,
            BigDecimal onHand,
            BigDecimal minimumStock,
            /** What the months behind us say the coming month will take. */
            BigDecimal forecastDemand,
            /** The seasonal lean applied, as a multiplier: 1.00 is a flat month. */
            BigDecimal seasonalFactor,
            /** Cover held back against a bad month, from how erratic this item has been. */
            BigDecimal safetyStock,
            /** What to buy: demand plus safety, less what is already on the shelf. */
            BigDecimal suggestedOrder,
            BigDecimal estimatedCost,
            UUID supplierId,
            String supplierName,
            /**
             * The facts behind the number, for the screen to put into a sentence in
             * whoever is reading's language. Deliberately not prose: the server does
             * not know whether this lands in a French, English or Arabic interface.
             */
            int monthsObserved,
            BigDecimal meanMonthly) {
    }

    public record OrderForecast(
            LocalDate periodStart,
            LocalDate periodEnd,
            int monthsOfHistory,
            BigDecimal totalEstimatedCost,
            List<OrderSuggestion> suggestions) {
    }
}
