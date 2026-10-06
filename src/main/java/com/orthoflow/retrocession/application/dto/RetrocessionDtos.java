package com.orthoflow.retrocession.application.dto;

import com.orthoflow.billing.domain.model.InvoiceStatus;
import com.orthoflow.billing.domain.model.PaymentMethod;
import com.orthoflow.retrocession.application.service.RetrocessionCalculator.Kind;
import com.orthoflow.retrocession.domain.model.Basis;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RetrocessionDtos {

    private RetrocessionDtos() {
    }

    // ── Rules ──
    public record RuleRequest(@NotNull UUID practitionerId, @NotNull Basis basis,
                              @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal ratePercent,
                              boolean deductLabFees, @DecimalMin("0") BigDecimal fixedMonthlyAmount,
                              @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
                              @Size(max = 2000) String notes, Map<String, BigDecimal> overrides) {
    }

    public record RuleView(UUID id, UUID practitionerId, String practitionerName, Basis basis, BigDecimal ratePercent,
                           boolean deductLabFees, BigDecimal fixedMonthlyAmount, LocalDate effectiveFrom,
                           LocalDate effectiveTo, String notes, Map<String, BigDecimal> overrides) {
    }

    // ── Simulation ──
    /** Narrow what counts. Both lists empty means everything that is not cancelled. */
    public record Filters(List<PaymentMethod> methods, List<InvoiceStatus> statuses) {
        public static Filters none() {
            return new Filters(List.of(), List.of());
        }

        public Filters {
            methods = methods == null ? List.of() : List.copyOf(methods);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
        }
    }

    public record LineView(Kind kind, LocalDate date, UUID invoiceId, String invoiceNumber, String patientCode,
                           String category, String label, BigDecimal base, BigDecimal ratePercent, BigDecimal amount) {
    }

    /** How an outstanding advance would be (or was) settled by the statement. */
    public record AdvanceSettlement(UUID advanceId, LocalDate advanceDate, BigDecimal advanceAmount, BigDecimal applied) {
    }

    public record PractitionerFigures(UUID practitionerId, String practitionerName, BigDecimal base, BigDecimal labDeduction,
                                      BigDecimal variable, BigDecimal fixed, BigDecimal adjustment, BigDecimal gross,
                                      BigDecimal advancesOutstanding, BigDecimal advancesApplied, BigDecimal net,
                                      List<AdvanceSettlement> settlements, List<LineView> lines) {
    }

    public record UnattributedInvoice(UUID invoiceId, String invoiceNumber, LocalDate date, BigDecimal amount) {
    }

    /**
     * Money no practitioner is credited with, so no rule can pay on it until someone assigns the invoice.
     * The count and list are what matter; when rules use different bases an invoice shows the larger of its
     * two figures, so the amount is an upper bound rather than an exact total.
     */
    public record Unattributed(BigDecimal amount, int invoiceCount, List<UnattributedInvoice> invoices) {
    }

    public record Simulation(LocalDate from, LocalDate to, Filters filters, List<PractitionerFigures> practitioners,
                             BigDecimal totalGross, BigDecimal totalNet, Unattributed unattributed) {
    }

    // ── Statements ──
    public record ValidateRequest(@NotNull UUID practitionerId, @NotNull LocalDate from, @NotNull LocalDate to,
                                  List<PaymentMethod> methods, List<InvoiceStatus> statuses, @Size(max = 2000) String notes) {
    }

    public record VoidRequest(@NotNull @Size(min = 3, max = 500) String reason) {
    }

    public enum PayStatus { UNPAID, PARTIAL, PAID, VOID }

    public record StatementSummary(UUID id, String number, UUID practitionerId, String practitionerName, LocalDate from,
                                   LocalDate to, BigDecimal gross, BigDecimal advancesDeducted, BigDecimal net,
                                   BigDecimal paid, PayStatus status, OffsetDateTime validatedAt) {
    }

    public record PayoutRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, LocalDate paidDate,
                                @Size(max = 32) String method, @Size(max = 128) String reference, @Size(max = 2000) String notes) {
    }

    public record PayoutView(UUID id, BigDecimal amount, LocalDate paidDate, String method, String reference, String notes,
                             OffsetDateTime recordedAt) {
    }

    public record StatementView(UUID id, String number, UUID practitionerId, String practitionerName, LocalDate from,
                                LocalDate to, BigDecimal base, BigDecimal labDeduction, BigDecimal variable, BigDecimal fixed,
                                BigDecimal adjustment, BigDecimal gross, BigDecimal advancesDeducted, BigDecimal net,
                                BigDecimal paid, PayStatus status, Filters filters, String notes, OffsetDateTime validatedAt,
                                OffsetDateTime voidedAt, String voidReason, List<LineView> lines,
                                List<AdvanceSettlement> settlements, List<PayoutView> payouts) {
    }

    // ── Advances ──
    public record AdvanceRequest(@NotNull UUID practitionerId, @NotNull @DecimalMin("0.01") BigDecimal amount,
                                 LocalDate advanceDate, @Size(max = 32) String method, @Size(max = 2000) String notes) {
    }

    public record AdvanceView(UUID id, UUID practitionerId, String practitionerName, LocalDate advanceDate, BigDecimal amount,
                              BigDecimal outstanding, String method, String notes) {
    }
}
