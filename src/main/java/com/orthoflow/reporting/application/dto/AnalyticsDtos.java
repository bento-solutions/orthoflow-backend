package com.orthoflow.reporting.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Practice analytics: procedure activity, doctor time, the income statement and goals. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    // ── Procedure activity ──
    public record ProcedureRow(UUID treatmentId, String treatmentName, String category, UUID practitionerId, String practitionerName,
                               String status, long sessions, BigDecimal revenue, BigDecimal materialCost, BigDecimal grossMargin,
                               BigDecimal marginPercent) {
    }

    public record GroupTotal(String key, String label, long sessions, BigDecimal revenue, BigDecimal materialCost, BigDecimal grossMargin) {
    }

    public record ProcedureActivity(LocalDate from, LocalDate to, List<ProcedureRow> rows, GroupTotal total,
                                    List<GroupTotal> byCategory, List<GroupTotal> byPractitioner) {
    }

    // ── Doctor time ──
    public record TimeRow(UUID practitionerId, String practitionerName, String appointmentType, long appointments,
                          BigDecimal activeMinutes, BigDecimal averageMinutes, BigDecimal medianMinutes,
                          BigDecimal plannedAverageMinutes, BigDecimal averageWaitMinutes) {
    }

    public record TimeIssue(UUID appointmentId, java.time.OffsetDateTime when, String practitionerName, String appointmentType,
                            String patientCode, String problem, BigDecimal minutes) {
    }

    /** How trustworthy the figures are: appointments whose stamps are missing or implausible are left out of them. */
    public record DataQuality(long considered, long valid, long missingEnd, long missingStart, long tooShort, long tooLong,
                              long invalidOrder, long completedWithoutTimes, List<TimeIssue> issues) {
    }

    public record DoctorTime(LocalDate from, LocalDate to, int minMinutes, int maxMinutes, List<TimeRow> byPractitioner,
                             List<TimeRow> byProcedure, DataQuality quality) {
    }

    // ── Income statement (CPC) ──
    public enum Group { DAY, WEEK, MONTH, YEAR }

    public enum RowKind { HEADING, LINE, SUBTOTAL, RESULT }

    public record Period(String key, String label, LocalDate from, LocalDate to) {
    }

    /** One line of the statement, with an amount per period (same order as {@code periods}) and the total. */
    public record StatementRow(String code, String label, RowKind kind, int level, List<BigDecimal> amounts, BigDecimal total) {
    }

    public record IncomeStatement(LocalDate from, LocalDate to, Group group, String basis, boolean includeRetrocessions,
                                  List<Period> periods, List<StatementRow> rows, List<String> notes) {
    }

    // ── Goals ──
    public record GoalInputs(BigDecimal fixedCosts, BigDecimal personalNeeds, BigDecimal variableCostPercent, BigDecimal workingDaysPerMonth) {
    }

    /** What the wizard works out from its inputs. */
    public record GoalPlan(GoalInputs inputs, BigDecimal breakEvenMonthly, BigDecimal monthlyTarget, BigDecimal dailyTarget,
                           BigDecimal yearlyTarget) {
    }

    public record GoalSuggestions(BigDecimal fixedCosts, BigDecimal variableCostPercent, BigDecimal workingDaysPerMonth,
                                  LocalDate basedOnFrom, LocalDate basedOnTo) {
    }

    public record GoalMonth(int month, BigDecimal target, BigDecimal actual, BigDecimal progressPercent, BigDecimal variance,
                            BigDecimal projected) {
    }

    public record GoalTracking(int year, String basis, GoalInputs inputs, BigDecimal monthlyTarget, List<GoalMonth> months,
                               BigDecimal yearTarget, BigDecimal yearActual, BigDecimal yearToDateTarget, BigDecimal yearToDateActual,
                               boolean saved) {
    }
}
