package com.orthoflow.reporting.application.service;

import com.orthoflow.reporting.application.dto.InventoryKPIResponse;
import com.orthoflow.reporting.application.dto.TopConsumedItemResponse;
import com.orthoflow.reporting.application.dto.TreatmentProfitabilityResponse;
import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.treatment.domain.model.*;
import com.orthoflow.inventory.domain.repository.CountSessionRepository;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.inventory.domain.repository.StockMovementRepository;
import com.orthoflow.treatment.domain.repository.TreatmentInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import com.orthoflow.inventory.domain.model.CountSession;
import com.orthoflow.inventory.domain.model.MovementType;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.inventory.domain.model.StockMovement;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.model.TreatmentInvoice;
import com.orthoflow.treatment.domain.model.TreatmentInvoiceStatus;

/**
 * BR07 / BR09 — read-only aggregate views over data owned by other services
 * in this module. Every query here is a single fetch folded in Java (or a
 * single aggregate DB query) — never a per-row loop issuing its own query.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final int DEFAULT_EXPIRING_DAYS_AHEAD = 30;
    private static final int TOP_CONSUMED_LOOKBACK_DAYS = 90;
    private static final int TOP_CONSUMED_LIMIT = 5;

    private final TreatmentInvoiceRepository treatmentInvoiceRepository;
    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CountSessionRepository countSessionRepository;

    public List<TreatmentProfitabilityResponse> getTreatmentProfitability() {
        // Summed by the database, per treatment: the clinic's invoices are never loaded into memory (audit P2).
        LinkedHashMap<UUID, TreatmentAccumulator> byTreatment = new LinkedHashMap<>();
        for (TreatmentInvoiceRepository.TreatmentTotals row : treatmentInvoiceRepository.finalizedTotalsByTreatment()) {
            TreatmentAccumulator acc = new TreatmentAccumulator(row.treatmentId(), row.treatmentName());
            acc.totalRevenue = nvl(row.revenue());
            acc.totalMaterialCost = nvl(row.materialCost());
            acc.sessionCount = row.sessions();
            byTreatment.put(row.treatmentId(), acc);
        }

        List<TreatmentProfitabilityResponse> result = new ArrayList<>();
        for (TreatmentAccumulator acc : byTreatment.values()) {
            BigDecimal grossMargin = acc.totalRevenue.subtract(acc.totalMaterialCost);
            BigDecimal marginPercent = acc.totalRevenue.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : grossMargin.multiply(BigDecimal.valueOf(100)).divide(acc.totalRevenue, 2, RoundingMode.HALF_UP);
            result.add(TreatmentProfitabilityResponse.builder()
                    .treatmentId(acc.treatmentId)
                    .treatmentName(acc.treatmentName)
                    .totalRevenue(acc.totalRevenue)
                    .totalMaterialCost(acc.totalMaterialCost)
                    .grossMargin(grossMargin)
                    .marginPercent(marginPercent)
                    .sessionCount(acc.sessionCount)
                    .build());
        }
        return result;
    }

    public InventoryKPIResponse getInventoryKPI() {
        StockItemRepository.ActiveTotals totals = stockItemRepository.activeTotals();
        BigDecimal currentInventoryValue = nvl(totals.value());

        long lowStockItemCount = stockItemRepository.findLowStock().size();
        long outOfStockItemCount = totals.outOfStock();

        LocalDate today = LocalDate.now();
        long expiringItemCount = stockItemRepository
                .findExpiring(today, today.plusDays(DEFAULT_EXPIRING_DAYS_AHEAD)).size();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay().atOffset(now.getOffset());
        BigDecimal monthlyInventoryCost = nvl(stockMovementRepository.costOfMovementsBetween(MovementType.OUT, monthStart, now));

        BigDecimal inventoryLossGainValue = BigDecimal.ZERO;
        BigDecimal inventoryVariancePercent = BigDecimal.ZERO;
        Optional<CountSession> latestValidated = countSessionRepository.findTopByStatusValidatedOrderByValidatedDateDesc();
        if (latestValidated.isPresent()) {
            inventoryLossGainValue = nvl(latestValidated.get().getTotalCostVariance());
            if (currentInventoryValue.compareTo(BigDecimal.ZERO) != 0) {
                inventoryVariancePercent = inventoryLossGainValue.multiply(BigDecimal.valueOf(100))
                        .divide(currentInventoryValue, 2, RoundingMode.HALF_UP);
            }
        }

        OffsetDateTime lookbackStart = now.minusDays(TOP_CONSUMED_LOOKBACK_DAYS);
        List<TopConsumedItemResponse> topConsumedItems = stockMovementRepository
                .topItemsBetween(MovementType.OUT, lookbackStart, now, TOP_CONSUMED_LIMIT).stream()
                .map(item -> TopConsumedItemResponse.builder()
                        .stockItemId(item.stockItemId())
                        .stockItemName(item.name())
                        .totalConsumed(nvl(item.quantity()))
                        .totalCost(nvl(item.quantity()).multiply(nvl(item.purchasePrice())))
                        .build())
                .toList();

        return InventoryKPIResponse.builder()
                .currentInventoryValue(currentInventoryValue)
                .lowStockItemCount(lowStockItemCount)
                .outOfStockItemCount(outOfStockItemCount)
                .expiringItemCount(expiringItemCount)
                .monthlyInventoryCost(monthlyInventoryCost)
                .inventoryVariancePercent(inventoryVariancePercent)
                .inventoryLossGainValue(inventoryLossGainValue)
                .topConsumedItems(topConsumedItems)
                .build();
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static class TreatmentAccumulator {
        final UUID treatmentId;
        final String treatmentName;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalMaterialCost = BigDecimal.ZERO;
        long sessionCount = 0;

        TreatmentAccumulator(UUID treatmentId, String treatmentName) {
            this.treatmentId = treatmentId;
            this.treatmentName = treatmentName;
        }
    }
}
