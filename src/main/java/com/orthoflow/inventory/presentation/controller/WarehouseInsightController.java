package com.orthoflow.inventory.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.inventory.application.dto.WarehouseInsightDtos.LeakageReport;
import com.orthoflow.inventory.application.dto.WarehouseInsightDtos.OrderForecast;
import com.orthoflow.inventory.application.service.ConsumptionLeakageService;
import com.orthoflow.inventory.application.service.StockForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * The two questions a practice owner asks the warehouse: where did it go, and what
 * should we buy.
 *
 * <p>Both name doctors and both carry costs, so both sit behind the analytics
 * permission rather than the general stock one — this is not information the front desk
 * needs to do its job.
 */
@RestController
@RequestMapping("/stock/insights")
@RequiredArgsConstructor
public class WarehouseInsightController {

    private final ConsumptionLeakageService leakage;
    private final StockForecastService forecast;
    private final CurrentUserProvider currentUser;

    /** Expected consumption against what really left the shelf, attributed by doctor. */
    @GetMapping("/leakage")
    @PreAuthorize("hasAuthority('ANALYTICS_VIEW')")
    public LeakageReport leakage(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return leakage.report(currentUser.requirePracticeId(), from, to);
    }

    /** What to order for a month, defaulting to the next one. */
    @GetMapping("/forecast")
    @PreAuthorize("hasAuthority('ANALYTICS_VIEW')")
    public OrderForecast forecast(@RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {
        return forecast.forecast(currentUser.requirePracticeId(), month);
    }
}
