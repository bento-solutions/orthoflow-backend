package com.orthoflow.reporting.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.GoalInputs;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.GoalPlan;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.Group;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.Period;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PracticeAnalyticsUnitTest {

    private static GoalInputs inputs(String fixed, String personal, String variable, String days) {
        return new GoalInputs(new BigDecimal(fixed), new BigDecimal(personal), new BigDecimal(variable), new BigDecimal(days));
    }

    private final GoalService goals = new GoalService(null, null, null, null);

    // ── Goals ──
    @Test
    void theMonthlyTargetCoversFixedCostsAndTheOwnerAfterVariableCosts() {
        GoalPlan plan = goals.plan(inputs("20000", "15000", "20", "22"));

        // (20000 + 15000) / 0.8
        assertThat(plan.monthlyTarget()).isEqualByComparingTo("43750.00");
        assertThat(plan.breakEvenMonthly()).isEqualByComparingTo("25000.00");
        assertThat(plan.dailyTarget()).isEqualByComparingTo("1988.64");
        assertThat(plan.yearlyTarget()).isEqualByComparingTo("525000.00");
    }

    @Test
    void withNoVariableCostsTheTargetIsJustTheTwoNeedsAdded() {
        assertThat(goals.plan(inputs("10000", "5000", "0", "20")).monthlyTarget()).isEqualByComparingTo("15000.00");
    }

    @Test
    void nonsenseInputsAreRefused() {
        assertThatThrownBy(() -> goals.plan(inputs("-1", "0", "0", "22"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> goals.plan(inputs("1", "0", "100", "22"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> goals.plan(inputs("1", "0", "10", "0"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> goals.plan(inputs("1", "0", "10", "32"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> goals.plan(new GoalInputs(null, null, null, null))).isInstanceOf(ValidationException.class);
    }

    @Test
    void theCurrentMonthIsProjectedAtThePaceOfTheDaysAlreadyWorked() {
        // March 2026 has 26 open days when Sunday is closed; by Tuesday the 10th, 8 of them have passed.
        BigDecimal projected = GoalService.projectMonth(new BigDecimal("10000"), YearMonth.of(2026, 3), LocalDate.of(2026, 3, 10), Set.of(1, 2, 3, 4, 5, 6));
        assertThat(projected).isEqualByComparingTo("32500.00");
    }

    @Test
    void aMonthNotYetStartedProjectsToWhatWasDone() {
        BigDecimal projected = GoalService.projectMonth(new BigDecimal("0"), YearMonth.of(2026, 3), LocalDate.of(2026, 3, 1), Set.of(2, 3, 4, 5, 6));
        // March 1st 2026 is a Sunday, so nothing has elapsed on an open day yet.
        assertThat(projected).isEqualByComparingTo("0");
    }

    // ── Income statement columns ──
    @Test
    void weeksStartWhereThePeriodStartsAndEndOnSunday() {
        List<Period> weeks = IncomeStatementService.periods(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 18), Group.WEEK);

        assertThat(weeks).extracting(Period::from).containsExactly(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 16));
        assertThat(weeks).extracting(Period::to).containsExactly(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 18));
        assertThat(weeks.get(1).label()).isEqualTo("2026-W11");
    }

    @Test
    void monthsAndYearsAreClippedToThePeriod() {
        List<Period> months = IncomeStatementService.periods(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 10), Group.MONTH);
        assertThat(months).extracting(Period::label).containsExactly("2026-01", "2026-02", "2026-03");
        assertThat(months.get(0).from()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(months.get(1).to()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(months.get(2).to()).isEqualTo(LocalDate.of(2026, 3, 10));

        List<Period> years = IncomeStatementService.periods(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 2, 1), Group.YEAR);
        assertThat(years).extracting(Period::label).containsExactly("2025", "2026");
        assertThat(years.get(0).to()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void everyDayIsItsOwnColumn() {
        assertThat(IncomeStatementService.periods(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 3, 2), Group.DAY)).hasSize(4);
    }

    @Test
    void anOverlongGroupingIsRefusedBeforeAnyQueryRuns() {
        IncomeStatementService service = new IncomeStatementService(null, null);
        assertThatThrownBy(() -> service.statement(java.util.UUID.randomUUID(), LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1), Group.DAY, "COLLECTED", false, "fr"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("columns");
    }

    // ── Doctor time ──
    @Test
    void theMedianIsTheMiddleValueOrTheMeanOfTheTwoMiddleOnes() {
        assertThat(DoctorTimeService.median(new double[]{1, 2, 3})).isEqualTo(2.0);
        assertThat(DoctorTimeService.median(new double[]{1, 2, 3, 4})).isEqualTo(2.5);
        assertThat(DoctorTimeService.median(new double[]{7})).isEqualTo(7.0);
    }
}
