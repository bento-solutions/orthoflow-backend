package com.orthoflow.reporting.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a given taxable income would owe under a schedule the clinic has entered.
 *
 * <p>No rates are built in. They differ by year and by taxpayer, and a wrong figure in a
 * simulation is worse than none, so the clinic enters the bands and where they come from, and this
 * only does the arithmetic. The result always carries that source. It is a simulation, not tax
 * advice.
 */
@Service
@RequiredArgsConstructor
public class TaxSimulationService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_BANDS = 12;

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public TaxSchedule schedule(UUID practiceId, int year) {
        checkYear(year);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, source, dependent_deduction, max_dependents FROM practice_tax_schedules WHERE practice_id = ? AND tax_year = ?",
                practiceId, year);
        if (rows.isEmpty()) {
            return new TaxSchedule(year, false, null, List.of(), BigDecimal.ZERO, 0);
        }
        Map<String, Object> s = rows.get(0);
        List<TaxBracket> brackets = jdbc.query(
                "SELECT up_to, rate_percent FROM practice_tax_brackets WHERE schedule_id = ? ORDER BY position",
                (rs, i) -> new TaxBracket(rs.getBigDecimal("up_to"), rs.getBigDecimal("rate_percent")), s.get("id"));
        return new TaxSchedule(year, true, (String) s.get("source"), brackets, (BigDecimal) s.get("dependent_deduction"),
                ((Number) s.get("max_dependents")).intValue());
    }

    @Transactional
    public TaxSchedule save(UUID practiceId, UUID actorId, int year, SaveTaxSchedule body) {
        checkYear(year);
        validate(body);
        BigDecimal deduction = body.dependentDeduction() == null ? BigDecimal.ZERO : body.dependentDeduction();
        int maxDependents = body.maxDependents() == null ? 0 : body.maxDependents();
        UUID id = jdbc.queryForObject("""
                INSERT INTO practice_tax_schedules (id, practice_id, tax_year, source, dependent_deduction, max_dependents, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (practice_id, tax_year) DO UPDATE SET source = EXCLUDED.source,
                    dependent_deduction = EXCLUDED.dependent_deduction, max_dependents = EXCLUDED.max_dependents,
                    updated_by = EXCLUDED.updated_by, updated_at = now(), version = practice_tax_schedules.version + 1
                RETURNING id""", UUID.class, UUID.randomUUID(), practiceId, year, body.source().trim(), deduction, maxDependents, actorId);
        jdbc.update("DELETE FROM practice_tax_brackets WHERE schedule_id = ?", id);
        for (int i = 0; i < body.brackets().size(); i++) {
            TaxBracket b = body.brackets().get(i);
            jdbc.update("INSERT INTO practice_tax_brackets (schedule_id, position, up_to, rate_percent) VALUES (?, ?, ?, ?)",
                    id, i, b.upTo(), b.ratePercent());
        }
        return schedule(practiceId, year);
    }

    @Transactional(readOnly = true)
    public TaxSimulation simulate(UUID practiceId, TaxSimulationInput in) {
        if (in.taxableIncome() == null) {
            throw new ValidationException("Enter the taxable income to simulate");
        }
        TaxSchedule schedule = schedule(practiceId, in.year());
        if (!schedule.configured()) {
            throw new ValidationException("No tax schedule has been entered for " + in.year()
                    + ". Enter the bands and their source first.");
        }
        int dependents = in.dependents() == null ? 0 : in.dependents();
        if (dependents < 0 || dependents > 50) {
            throw new ValidationException("Dependents out of range");
        }
        return compute(schedule, in.taxableIncome(), dependents);
    }

    /** The arithmetic alone, so it can be tested without a database. */
    static TaxSimulation compute(TaxSchedule schedule, BigDecimal income, int dependents) {
        BigDecimal taxable = income.max(BigDecimal.ZERO);
        List<TaxBand> bands = new ArrayList<>();
        BigDecimal lower = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        for (TaxBracket b : schedule.brackets()) {
            BigDecimal ceiling = b.upTo() == null ? taxable : taxable.min(b.upTo());
            BigDecimal inBand = ceiling.subtract(lower).max(BigDecimal.ZERO);
            BigDecimal tax = inBand.multiply(b.ratePercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            bands.add(new TaxBand(lower, b.upTo(), b.ratePercent(), inBand, tax));
            gross = gross.add(tax);
            if (b.upTo() != null) lower = b.upTo();
        }
        BigDecimal relief = schedule.dependentDeduction()
                .multiply(BigDecimal.valueOf(Math.min(dependents, schedule.maxDependents()))).min(gross);
        BigDecimal tax = gross.subtract(relief);
        BigDecimal effective = taxable.signum() == 0 ? BigDecimal.ZERO
                : tax.multiply(HUNDRED).divide(taxable, 2, RoundingMode.HALF_UP);
        return new TaxSimulation(schedule.year(), schedule.source(), income, dependents, bands, gross, relief, tax, effective,
                income.subtract(tax));
    }

    /** The rules a saved schedule must satisfy, kept apart so they can be tested directly. */
    static void validate(SaveTaxSchedule body) {
        if (body == null || body.source() == null || body.source().isBlank()) {
            throw new ValidationException("Say where these figures come from (the text of law and its year)");
        }
        if (body.source().trim().length() > 500) {
            throw new ValidationException("The source is too long (500 characters at most)");
        }
        List<TaxBracket> brackets = body.brackets();
        if (brackets == null || brackets.isEmpty() || brackets.size() > MAX_BANDS) {
            throw new ValidationException("Enter between 1 and " + MAX_BANDS + " bands");
        }
        BigDecimal previous = BigDecimal.ZERO;
        for (int i = 0; i < brackets.size(); i++) {
            TaxBracket b = brackets.get(i);
            boolean last = i == brackets.size() - 1;
            if (b.ratePercent() == null || b.ratePercent().signum() < 0 || b.ratePercent().compareTo(HUNDRED) > 0) {
                throw new ValidationException("Band " + (i + 1) + ": the rate must be between 0 and 100");
            }
            if (last) {
                if (b.upTo() != null) {
                    throw new ValidationException("The last band has no upper limit: leave it empty");
                }
            } else if (b.upTo() == null || b.upTo().compareTo(previous) <= 0) {
                throw new ValidationException("Band " + (i + 1) + ": each upper limit must be above the one before");
            } else {
                previous = b.upTo();
            }
        }
        if (body.dependentDeduction() != null && body.dependentDeduction().signum() < 0) {
            throw new ValidationException("The deduction per dependent cannot be negative");
        }
        if (body.maxDependents() != null && (body.maxDependents() < 0 || body.maxDependents() > 20)) {
            throw new ValidationException("The most dependents counted must be between 0 and 20");
        }
    }

    private static void checkYear(int year) {
        if (year < 2000 || year > 2100) {
            throw new ValidationException("Year out of range");
        }
    }
}
