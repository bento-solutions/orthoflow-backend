package com.orthoflow.finance.application.dto;

import com.orthoflow.billing.domain.model.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class FinanceDtos {

    private FinanceDtos() {
    }

    // ── Collections (daily cash) ──
    public record MethodTotal(PaymentMethod method, BigDecimal total, long count) {
    }

    public record CollectionRow(UUID receiptId, LocalDate date, BigDecimal amount, PaymentMethod method, String reference,
                                UUID patientId, String patientName, String patientCode, UUID practitionerId,
                                String practitionerName, BigDecimal unallocated) {
    }

    /**
     * What came in over a period. {@code received} is cash taken; {@code allocated}
     * what was applied to invoices (some of it from earlier advances, shown as
     * {@code advancesUsed}); {@code creditAvailable} what patients still hold.
     */
    public record CollectionsReport(LocalDate from, LocalDate to, BigDecimal received, BigDecimal allocated, BigDecimal advancesUsed,
                              BigDecimal creditAvailable, List<MethodTotal> byMethod, List<CollectionRow> rows) {
    }

    // ── Debts ──
    public record DebtRow(UUID patientId, String patientCode, String firstName, String lastName, String phone, BigDecimal fees,
                          BigDecimal paid, BigDecimal balance, BigDecimal credit, LocalDate lastInvoice, LocalDate lastPayment) {
    }

    public record DebtSummary(BigDecimal totalFees, BigDecimal totalPaid, BigDecimal totalOwed, BigDecimal totalCredit,
                              long patientsOwing, List<DebtRow> rows) {
    }

    // ── Dashboard ──
    public record Breakdown(String key, String label, BigDecimal amount) {
    }

    public record MonthPoint(String month, BigDecimal production, BigDecimal collections, BigDecimal expenses) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "FinanceDashboard")
    public record Dashboard(LocalDate from, LocalDate to, BigDecimal production, BigDecimal collections, BigDecimal patientDebt,
                            BigDecimal operatingExpenses, BigDecimal salaries, BigDecimal socialCharges, BigDecimal otherExpenses,
                            BigDecimal retrocessions, BigDecimal result, List<Breakdown> collectionsByMethod,
                            List<Breakdown> expensesByCategory, List<Breakdown> productionByPractitioner,
                            List<Breakdown> collectionsByPractitioner, List<MonthPoint> trend) {
    }

    // ── Cash closing ──
    public record CashLine(PaymentMethod method, BigDecimal expected, BigDecimal counted, BigDecimal difference) {
    }

    public record CashClosing(UUID id, LocalDate date, boolean closed, List<CashLine> lines, BigDecimal totalDifference,
                              String notes, OffsetDateTime closedAt) {
    }

    public record Counted(@NotNull PaymentMethod method, @NotNull @PositiveOrZero BigDecimal counted) {
    }

    public record CloseCash(@NotNull LocalDate date, @NotEmpty @Valid List<Counted> counts, String notes) {
    }
}
