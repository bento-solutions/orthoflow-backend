package com.orthoflow.reporting.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.*;
import com.orthoflow.reporting.infrastructure.ReportingQuery;
import com.orthoflow.reporting.infrastructure.ReportingQuery.MonthAmount;
import com.orthoflow.scheduling.application.port.OpeningHoursProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * A revenue target and how the year is tracking against it. The wizard turns what
 * the owner needs (fixed costs, personal needs, the share of each fee that goes on
 * variable costs, working days) into the monthly revenue that covers it; saving
 * keeps the inputs so the wizard reopens where it was left.
 *
 * <p>The target is revenue R such that R minus the variable costs on it pays for
 * the fixed costs and the owner: {@code R = (fixed + personal) / (1 - variable%)}.
 */
@Service
@RequiredArgsConstructor
public class GoalService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DEFAULT_WORKING_DAYS = new BigDecimal("22.0");

    private final ReportingQuery query;
    private final JdbcTemplate jdbc;
    private final PracticeZone practiceZone;
    private final OpeningHoursProvider openingHours;

    // ── Wizard ──
    public GoalPlan plan(GoalInputs in) {
        validate(in);
        BigDecimal margin = BigDecimal.ONE.subtract(in.variableCostPercent().divide(HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal breakEven = in.fixedCosts().divide(margin, 2, RoundingMode.HALF_UP);
        BigDecimal target = in.fixedCosts().add(in.personalNeeds()).divide(margin, 2, RoundingMode.HALF_UP);
        BigDecimal daily = target.divide(in.workingDaysPerMonth(), 2, RoundingMode.HALF_UP);
        return new GoalPlan(in, breakEven, target, daily, target.multiply(BigDecimal.valueOf(12)));
    }

    /** Starting values drawn from the last three full months, so the wizard opens on the clinic's own numbers. */
    @Transactional(readOnly = true)
    public GoalSuggestions suggestions(UUID practiceId) {
        LocalDate today = LocalDate.now(practiceZone.of(practiceId));
        LocalDate from = today.withDayOfMonth(1).minusMonths(3);
        LocalDate to = today.withDayOfMonth(1).minusDays(1);
        Map<String, BigDecimal> kinds = new HashMap<>();
        query.expensesByKind(practiceId, from, to).forEach(b -> kinds.put(b.key(), b.amount()));
        BigDecimal fixed = List.of("OPERATING", "SALARY", "SOCIAL", "TAX").stream().map(k -> kinds.getOrDefault(k, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        BigDecimal variable = List.of("LAB", "SUPPLIES").stream().map(k -> kinds.getOrDefault(k, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = query.collected(practiceId, from, to);
        BigDecimal percent = collected.signum() == 0 ? BigDecimal.ZERO
                : variable.multiply(HUNDRED).divide(collected, 2, RoundingMode.HALF_UP).min(new BigDecimal("95"));
        long openDays = openWeekdays(practiceId).size();
        BigDecimal days = openDays == 0 ? DEFAULT_WORKING_DAYS
                : BigDecimal.valueOf(openDays * 52.0 / 12.0).setScale(1, RoundingMode.HALF_UP);
        return new GoalSuggestions(fixed, percent, days, from, to);
    }

    // ── Save and track ──
    @Transactional
    public GoalTracking save(UUID practiceId, UUID actorId, int year, String basis, GoalInputs in) {
        checkYear(year);
        GoalPlan plan = plan(in);
        String b = "PRODUCED".equalsIgnoreCase(basis) ? "PRODUCED" : "COLLECTED";
        jdbc.update("""
                INSERT INTO practice_goals (id, practice_id, goal_year, basis, fixed_costs, personal_needs, variable_cost_percent,
                    working_days_per_month, monthly_target, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (practice_id, goal_year) DO UPDATE SET basis = EXCLUDED.basis, fixed_costs = EXCLUDED.fixed_costs,
                    personal_needs = EXCLUDED.personal_needs, variable_cost_percent = EXCLUDED.variable_cost_percent,
                    working_days_per_month = EXCLUDED.working_days_per_month, monthly_target = EXCLUDED.monthly_target,
                    updated_by = EXCLUDED.updated_by, updated_at = now(), version = practice_goals.version + 1""",
                UUID.randomUUID(), practiceId, year, b, in.fixedCosts(), in.personalNeeds(), in.variableCostPercent(),
                in.workingDaysPerMonth(), plan.monthlyTarget(), actorId);
        return tracking(practiceId, year);
    }

    @Transactional(readOnly = true)
    public GoalTracking tracking(UUID practiceId, int year) {
        checkYear(year);
        List<Map<String, Object>> saved = jdbc.queryForList(
                "SELECT basis, fixed_costs, personal_needs, variable_cost_percent, working_days_per_month, monthly_target FROM practice_goals WHERE practice_id = ? AND goal_year = ?",
                practiceId, year);
        if (saved.isEmpty()) {
            return new GoalTracking(year, "COLLECTED", null, BigDecimal.ZERO, List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        Map<String, Object> g = saved.get(0);
        String basis = (String) g.get("basis");
        BigDecimal target = (BigDecimal) g.get("monthly_target");
        GoalInputs inputs = new GoalInputs((BigDecimal) g.get("fixed_costs"), (BigDecimal) g.get("personal_needs"),
                (BigDecimal) g.get("variable_cost_percent"), (BigDecimal) g.get("working_days_per_month"));

        Map<Integer, BigDecimal> actual = new HashMap<>();
        for (MonthAmount m : "PRODUCED".equals(basis) ? query.producedByMonth(practiceId, year) : query.collectedByMonth(practiceId, year)) {
            actual.put(m.month(), m.amount());
        }
        LocalDate today = LocalDate.now(practiceZone.of(practiceId));
        Set<Integer> open = openWeekdays(practiceId);

        List<GoalMonth> months = new ArrayList<>();
        BigDecimal ytdTarget = BigDecimal.ZERO;
        BigDecimal ytdActual = BigDecimal.ZERO;
        BigDecimal yearActual = BigDecimal.ZERO;
        for (int month = 1; month <= 12; month++) {
            BigDecimal done = actual.getOrDefault(month, BigDecimal.ZERO);
            yearActual = yearActual.add(done);
            YearMonth ym = YearMonth.of(year, month);
            boolean past = ym.isBefore(YearMonth.from(today));
            boolean current = ym.equals(YearMonth.from(today));
            if (past || current) {
                ytdTarget = ytdTarget.add(target);
                ytdActual = ytdActual.add(done);
            }
            BigDecimal projected = null;
            if (past) {
                projected = done;
            } else if (current) {
                projected = projectMonth(done, ym, today, open);
            }
            months.add(new GoalMonth(month, target, done, ProcedureActivityService.percent(done, target), done.subtract(target), projected));
        }
        return new GoalTracking(year, basis, inputs, target, months, target.multiply(BigDecimal.valueOf(12)), yearActual, ytdTarget, ytdActual, true);
    }

    /** Month-to-date scaled by open days: at the pace so far, where does the month land? */
    static BigDecimal projectMonth(BigDecimal done, YearMonth month, LocalDate today, Set<Integer> openWeekdays) {
        long total = 0;
        long elapsed = 0;
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            if (openWeekdays.contains(d.getDayOfWeek().getValue())) {
                total++;
                if (!d.isAfter(today)) {
                    elapsed++;
                }
            }
        }
        if (elapsed == 0 || total == 0) {
            return done;
        }
        return done.multiply(BigDecimal.valueOf(total)).divide(BigDecimal.valueOf(elapsed), 2, RoundingMode.HALF_UP);
    }

    /** ISO weekdays on which the clinic is open; Monday to Friday when no hours are configured. */
    private Set<Integer> openWeekdays(UUID practiceId) {
        Set<Integer> open = new TreeSet<>();
        for (int day = 1; day <= 7; day++) {
            if (openingHours.forWeekday(practiceId, day).isPresent()) {
                open.add(day);
            }
        }
        return open.isEmpty() ? new TreeSet<>(List.of(1, 2, 3, 4, 5)) : open;
    }

    private static void validate(GoalInputs in) {
        if (in == null || in.fixedCosts() == null || in.personalNeeds() == null || in.variableCostPercent() == null || in.workingDaysPerMonth() == null) {
            throw new ValidationException("Fixed costs, personal needs, variable cost percentage and working days are all required");
        }
        if (in.fixedCosts().signum() < 0 || in.personalNeeds().signum() < 0) {
            throw new ValidationException("Costs and needs cannot be negative");
        }
        if (in.variableCostPercent().signum() < 0 || in.variableCostPercent().compareTo(HUNDRED) >= 0) {
            throw new ValidationException("Variable costs must be a percentage below 100");
        }
        if (in.workingDaysPerMonth().signum() <= 0 || in.workingDaysPerMonth().compareTo(BigDecimal.valueOf(31)) > 0) {
            throw new ValidationException("Working days per month must be between 1 and 31");
        }
    }

    private static void checkYear(int year) {
        if (year < 2000 || year > 2100) {
            throw new ValidationException("Year out of range");
        }
    }
}
