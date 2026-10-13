package com.orthoflow.inventory.application.service;

import com.orthoflow.inventory.application.dto.WarehouseInsightDtos.OrderForecast;
import com.orthoflow.inventory.application.dto.WarehouseInsightDtos.OrderSuggestion;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.inventory.infrastructure.adapter.persistence.StockForecastJpaRepository;
import com.orthoflow.inventory.infrastructure.adapter.persistence.StockForecastJpaRepository.MonthlyUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * What to order next month, argued rather than averaged.
 *
 * <p>A plain average of past consumption is a bad buyer: it ignores what is already on
 * the shelf, it buys the same amount in a quiet August as a busy October, and it holds
 * no cover for an item whose use swings wildly. This does four things instead.
 *
 * <ol>
 *   <li><b>Baseline</b> — mean monthly consumption over whole months only. The current,
 *       part-finished month is excluded; averaging it in drags every forecast down.
 *   <li><b>Seasonality</b> — if the target calendar month has been seen at least twice
 *       before, its historical index against the overall mean is applied. Below that it
 *       is left at 1.00 and the rationale says why: inventing a seasonal lean from a
 *       single observation is how you end up with a storeroom full of August.
 *   <li><b>Safety stock</b> — from how erratic the item has actually been (its standard
 *       deviation), not a flat percentage. A steady item needs almost none; a spiky one
 *       needs real cover.
 *   <li><b>Net of what is on hand</b> — the suggestion is what to buy, not what will be
 *       used. An item with three months on the shelf is suggested at zero.
 * </ol>
 *
 * <p>Every line carries a {@code rationale} in plain words, because a buying suggestion
 * nobody can argue with is a buying suggestion nobody should follow.
 */
@Service
@RequiredArgsConstructor
public class StockForecastService {

    /** Enough to see a season, short enough to still describe the practice as it is now. */
    private static final int LOOKBACK_MONTHS = 12;

    /** Below this many observations of a calendar month, a seasonal lean is a guess. */
    private static final int MIN_MONTHS_FOR_SEASONALITY = 2;

    /** Cover held against variability, as a multiple of the standard deviation. */
    private static final BigDecimal SAFETY_SIGMAS = new BigDecimal("0.5");

    private final StockForecastJpaRepository forecastRepository;
    private final StockItemRepository stockItemRepository;

    @Transactional(readOnly = true)
    public OrderForecast forecast(UUID practiceId, LocalDate targetMonth) {
        LocalDate target = targetMonth == null
                ? LocalDate.now().withDayOfMonth(1).plusMonths(1)
                : targetMonth.withDayOfMonth(1);
        LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);

        OffsetDateTime since = thisMonth.minusMonths(LOOKBACK_MONTHS).atStartOfDay().atOffset(ZoneOffset.UTC);

        // Whole months only — the month in progress is not a data point yet.
        Map<UUID, Map<LocalDate, BigDecimal>> history = forecastRepository.monthlyUsageSince(practiceId, since)
                .stream()
                .filter(u -> u.getMonth().isBefore(thisMonth))
                .collect(Collectors.groupingBy(MonthlyUsage::getStockItemId,
                        Collectors.toMap(MonthlyUsage::getMonth, MonthlyUsage::getQuantity, BigDecimal::add)));

        int monthsOfHistory = history.values().stream().mapToInt(Map::size).max().orElse(0);

        List<OrderSuggestion> suggestions = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        // @TenantId already scopes this to the current practice.
        for (StockItem item : stockItemRepository.findAll()) {
            if (!item.isActive()) {
                continue;
            }
            Map<LocalDate, BigDecimal> months = history.getOrDefault(item.getId(), Map.of());
            if (months.isEmpty()) {
                continue;   // never used: nothing to go on, and nothing to suggest
            }

            List<BigDecimal> values = new ArrayList<>(months.values());
            BigDecimal mean = mean(values);
            if (mean.signum() == 0) {
                continue;
            }

            // ── Seasonality ──
            List<BigDecimal> sameMonth = months.entrySet().stream()
                    .filter(e -> e.getKey().getMonthValue() == target.getMonthValue())
                    .map(Map.Entry::getValue).toList();
            boolean seasonal = sameMonth.size() >= MIN_MONTHS_FOR_SEASONALITY;
            BigDecimal factor = seasonal
                    ? mean(sameMonth).divide(mean, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ONE;
            // A lean beyond ±50% from thin history is noise, not a season.
            factor = factor.max(new BigDecimal("0.5")).min(new BigDecimal("1.5"));

            BigDecimal demand = mean.multiply(factor).setScale(2, RoundingMode.HALF_UP);
            BigDecimal safety = stdDev(values, mean).multiply(SAFETY_SIGMAS).setScale(2, RoundingMode.HALF_UP);

            BigDecimal onHand = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
            BigDecimal floor = item.getMinimumStock() == null ? BigDecimal.ZERO : item.getMinimumStock();

            // Buy enough to cover the month and still be above the reorder floor.
            BigDecimal need = demand.add(safety).add(floor).subtract(onHand).max(BigDecimal.ZERO);
            BigDecimal order = item.isDecimalSupported()
                    ? need.setScale(2, RoundingMode.HALF_UP)
                    : need.setScale(0, RoundingMode.CEILING);

            if (order.signum() == 0) {
                continue;   // the shelf already covers it
            }

            BigDecimal unitCost = item.getPricePerUse() == null ? BigDecimal.ZERO : item.getPricePerUse();
            BigDecimal cost = order.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            totalCost = totalCost.add(cost);

            suggestions.add(new OrderSuggestion(
                    item.getId(), item.getName(),
                    item.getUnitOfMeasure() == null ? null : item.getUnitOfMeasure().name(),
                    onHand, floor, demand, factor.setScale(2, RoundingMode.HALF_UP), safety, order, cost,
                    item.getSupplier() == null ? null : item.getSupplier().getId(),
                    item.getSupplier() == null ? null : item.getSupplier().getName(),
                    months.size(), mean.setScale(1, RoundingMode.HALF_UP)));
        }

        suggestions.sort(Comparator.comparing(OrderSuggestion::estimatedCost).reversed());
        return new OrderForecast(target, target.plusMonths(1).minusDays(1), monthsOfHistory,
                totalCost.setScale(2, RoundingMode.HALF_UP), suggestions);
    }


    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal stdDev(List<BigDecimal> values, BigDecimal mean) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal sumSq = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double variance = sumSq.divide(BigDecimal.valueOf(values.size() - 1), 6, RoundingMode.HALF_UP).doubleValue();
        return BigDecimal.valueOf(Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
    }
}
