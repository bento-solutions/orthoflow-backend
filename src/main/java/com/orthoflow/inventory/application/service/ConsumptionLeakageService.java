package com.orthoflow.inventory.application.service;

import com.orthoflow.inventory.application.dto.WarehouseInsightDtos.*;
import com.orthoflow.inventory.infrastructure.adapter.persistence.ConsumptionLeakageJpaRepository;
import com.orthoflow.inventory.infrastructure.adapter.persistence.ConsumptionLeakageJpaRepository.LeakageRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Where the stock actually went: expected consumption from the bill of materials, set
 * against what really left the shelf, with the difference attributed.
 *
 * <p>The whole design of this report is about not accusing the wrong thing. Three
 * filters stand between a difference and a doctor's name:
 *
 * <ol>
 *   <li><b>Tolerance.</b> Each recipe line carries a band. A syringe is not drawn to the
 *       microlitre, so anything inside it is simply how the work went.
 *   <li><b>Explanation.</b> Excess attributed to wastage, breakage or a patient factor
 *       is consumption that genuinely happened. It is reported, but separately.
 *   <li><b>Re-treatment.</b> Redoing a bond consumes the kit again, legitimately. It is
 *       excluded outright, because counting it would rank whoever takes the hard cases
 *       worst — the opposite of what this report is for.
 * </ol>
 *
 * <p>What survives all three is the unexplained remainder. That is the only figure here
 * that points at anybody, and even then it is published beside
 * {@code attributionCoveragePct}: when little consumption is recorded against
 * treatments at all, every number here is weaker than it looks, and the report says so
 * rather than letting a reader assume otherwise.
 */
@Service
@RequiredArgsConstructor
public class ConsumptionLeakageService {

    private static final int MONEY = 2;
    private static final int QTY = 3;

    private final ConsumptionLeakageJpaRepository repository;

    @Transactional(readOnly = true)
    public LeakageReport report(UUID practiceId, LocalDate from, LocalDate to) {
        List<LeakageRow> rows = repository.leakageByPractitioner(practiceId, from, to);
        // Re-treatments are excluded from the leakage query on purpose, so they need
        // fetching separately or "tracked separately" would be an empty promise.
        Map<UUID, ConsumptionLeakageJpaRepository.RetreatmentRow> retreats =
                repository.retreatmentByPractitioner(practiceId, from, to).stream()
                        .collect(Collectors.toMap(ConsumptionLeakageJpaRepository.RetreatmentRow::getPractitionerId,
                                r -> r, (a, b) -> a));
        List<UnattributedConsumption> unattributed = repository.unattributedConsumption(practiceId, from, to)
                .stream()
                .map(r -> new UnattributedConsumption(r.getItemName(), r.getUnitOfMeasure(), r.getSourceType(),
                        scale(r.getQuantity(), QTY), scale(r.getCost(), MONEY)))
                .toList();

        Map<UUID, List<LeakageLine>> byPractitioner = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Integer> treatments = new HashMap<>();

        BigDecimal totalUnexplained = BigDecimal.ZERO;
        BigDecimal totalExplained = BigDecimal.ZERO;
        BigDecimal totalRetreatment = BigDecimal.ZERO;
        BigDecimal totalExpectedCost = BigDecimal.ZERO;
        BigDecimal totalActualQty = BigDecimal.ZERO;

        for (LeakageRow r : rows) {
            BigDecimal expected = nz(r.getExpectedQty());
            BigDecimal tolerated = nz(r.getToleratedQty());
            BigDecimal actual = nz(r.getActualQty());
            BigDecimal explained = nz(r.getExplainedQty());
            BigDecimal retreat = nz(r.getRetreatmentQty());
            BigDecimal cost = nz(r.getUnitCost());

            // Everything above the tolerated ceiling, less what was accounted for.
            // Using less than expected is not a gap — it is good practice, or a recipe
            // that wants revisiting, and either way it is not a loss.
            BigDecimal excess = actual.subtract(tolerated).max(BigDecimal.ZERO);
            BigDecimal unexplained = excess.subtract(explained).max(BigDecimal.ZERO);

            BigDecimal unexplainedCost = unexplained.multiply(cost);
            totalUnexplained = totalUnexplained.add(unexplainedCost);
            totalExplained = totalExplained.add(explained.multiply(cost));
            totalRetreatment = totalRetreatment.add(retreat.multiply(cost));   // reasons logged on non-redone work
            totalExpectedCost = totalExpectedCost.add(expected.multiply(cost));
            totalActualQty = totalActualQty.add(actual);

            UUID pid = r.getPractitionerId();
            names.putIfAbsent(pid, r.getPractitionerName());
            treatments.merge(pid, r.getTreatments(), Math::max);
            byPractitioner.computeIfAbsent(pid, k -> new ArrayList<>())
                    .add(new LeakageLine(pid, r.getPractitionerName(), r.getStockItemId(), r.getItemName(),
                            r.getUnitOfMeasure(), r.getTreatments(),
                            scale(expected, QTY), scale(tolerated, QTY), scale(actual, QTY),
                            scale(explained, QTY), scale(retreat, QTY), scale(unexplained, QTY),
                            scale(unexplainedCost, MONEY)));
        }

        List<LeakageByDoctor> doctors = byPractitioner.entrySet().stream()
                .map(e -> {
                    BigDecimal cost = e.getValue().stream()
                            .map(LeakageLine::unexplainedCost)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal expectedCost = e.getValue().stream()
                            .map(l -> l.expected().multiply(unitCostOf(rows, l)))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    var rt = retreats.get(e.getKey());
                    return new LeakageByDoctor(e.getKey(), names.get(e.getKey()),
                            treatments.getOrDefault(e.getKey(), 0),
                            rt == null ? 0 : nz(rt.getRetreatments()).intValue(),
                            scale(rt == null ? BigDecimal.ZERO : rt.getCost(), MONEY),
                            scale(cost, MONEY),
                            percent(cost, expectedCost),
                            // Worst line first: that is the one worth a conversation.
                            e.getValue().stream()
                                    .sorted(Comparator.comparing(LeakageLine::unexplainedCost).reversed())
                                    .toList());
                })
                .sorted(Comparator.comparing(LeakageByDoctor::unexplainedCost).reversed())
                .toList();

        BigDecimal unattributedCost = unattributed.stream()
                .map(UnattributedConsumption::cost).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal retreatmentCost = retreats.values().stream()
                .map(r -> nz(r.getCost())).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(totalRetreatment);

        return new LeakageReport(from, to,
                scale(totalUnexplained, MONEY), scale(totalExplained, MONEY),
                scale(retreatmentCost, MONEY), scale(unattributedCost, MONEY),
                coverage(totalActualQty, unattributed),
                doctors, unattributed);
    }

    /**
     * How much of the period's consumption could be tied to a treatment at all. A report
     * covering a fifth of what left the shelf is not a report about leakage, and this is
     * what lets a reader see that at a glance.
     */
    private static BigDecimal coverage(BigDecimal attributedQty, List<UnattributedConsumption> unattributed) {
        BigDecimal other = unattributed.stream()
                .map(UnattributedConsumption::quantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return percent(attributedQty, attributedQty.add(other));
    }

    private static BigDecimal unitCostOf(List<LeakageRow> rows, LeakageLine line) {
        return rows.stream()
                .filter(r -> r.getStockItemId().equals(line.stockItemId()))
                .findFirst().map(r -> nz(r.getUnitCost())).orElse(BigDecimal.ZERO);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, MONEY, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale(BigDecimal v, int places) {
        return nz(v).setScale(places, RoundingMode.HALF_UP);
    }
}
