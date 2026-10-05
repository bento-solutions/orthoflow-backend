package com.orthoflow.finance.presentation;

import com.orthoflow.billing.domain.model.PaymentMethod;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.export.application.service.ExportService;
import com.orthoflow.finance.application.dto.FinanceDtos.*;
import com.orthoflow.finance.application.service.FinanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Money screens: collections and the daily cash, the debt list, the financial
 * dashboard. Each report has a {@code /export} twin returning pdf, xlsx or csv.
 * Authorities are in SecurityConfig: seeing totals is FINANCE_VIEW, closing the
 * cash is FINANCE_MANAGE.
 */
@RestController
@RequestMapping("/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService service;
    private final ExportService exportService;
    private final CurrentUserProvider currentUser;

    @GetMapping("/collections")
    public CollectionsReport collections(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(required = false) PaymentMethod method,
                                   @RequestParam(required = false) UUID practitionerId) {
        return service.collections(currentUser.requirePracticeId(), from, to, method, practitionerId);
    }

    @GetMapping("/debts")
    public DebtSummary debts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             @RequestParam(defaultValue = "true") boolean debtOnly,
                             @RequestParam(required = false) String search,
                             @RequestParam(defaultValue = "balance") String sort) {
        return service.debts(currentUser.requirePracticeId(), from, to, debtOnly, search, sort);
    }

    @GetMapping("/debts/export")
    public ResponseEntity<byte[]> exportDebts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(defaultValue = "true") boolean debtOnly,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(defaultValue = "pdf") String format,
                                              @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        return exportService.respond(service.debtTable(service.debts(practice, from, to, debtOnly, search, "balance"), from, to, lang),
                format, "situation-patients", practice, lang);
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.dashboard(currentUser.requirePracticeId(), from, to);
    }

    @GetMapping("/dashboard/export")
    public ResponseEntity<byte[]> exportDashboard(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(defaultValue = "pdf") String format,
                                                  @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        return exportService.respond(service.dashboardTable(service.dashboard(practice, from, to), lang), format,
                "tableau-de-bord-" + from + "-" + to, practice, lang);
    }

    @GetMapping("/cash-closing/{date}")
    public CashClosing cashClosing(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.cashClosing(currentUser.requirePracticeId(), date);
    }

    @PostMapping("/cash-closing")
    @ResponseStatus(HttpStatus.CREATED)
    public CashClosing close(@Valid @RequestBody CloseCash request) {
        return service.close(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }
}
