package com.orthoflow.retrocession.application.service;

import com.orthoflow.retrocession.domain.model.Basis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * What a practitioner is owed for a period: the one place the arithmetic lives.
 * It is a pure function of the rules and of the invoice and lab figures it is
 * handed, so the simulation screen, the dashboard and the validated statement can
 * never disagree about a number.
 *
 * <p>Rules, in short. Each billed item (a payment allocated to an invoice under
 * {@code COLLECTED}, an invoice under {@code PRODUCED}) is split by treatment
 * category; the percentage that applies is the one in force on the item's date, a
 * category override if the rule has one. If the rule deducts lab fees, each lab
 * bill received in the period is subtracted at the rule's standard percentage. A
 * fixed monthly amount accrues day by day, so a part month pays a part amount. A
 * period can never come out negative: a shortfall is shown as an explicit
 * adjustment line, because the clinic does not claw pay back through a statement.
 */
public final class RetrocessionCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int WORKING_SCALE = 10;

    private RetrocessionCalculator() {
    }

    public enum Kind { ITEM, LAB, FIXED, ADJUSTMENT }

    /** A rule as the calculator sees it. {@code to} null means open-ended. */
    public record Rule(UUID id, UUID practitionerId, Basis basis, BigDecimal ratePercent, boolean deductLabFees,
                       BigDecimal fixedMonthly, LocalDate from, LocalDate to, Map<String, BigDecimal> overrides) {

        boolean covers(LocalDate day) {
            return !day.isBefore(from) && (to == null || !day.isAfter(to));
        }

        BigDecimal rateFor(String category) {
            BigDecimal override = category == null || category.isBlank() ? null : overrides.get(category);
            return override != null ? override : ratePercent;
        }
    }

    /** Money attributable to one category of one invoice on one day. */
    public record Item(Basis basis, LocalDate date, UUID practitionerId, UUID invoiceId, String invoiceNumber,
                       String patientCode, String category, BigDecimal amount) {
    }

    public record LabFee(LocalDate date, UUID practitionerId, UUID labOrderId, String label, String patientCode,
                         BigDecimal amount) {
    }

    /** One row of a statement. {@code amount} is signed: lab deductions are negative. */
    public record Line(Kind kind, LocalDate date, UUID invoiceId, String invoiceNumber, String patientCode,
                       String category, String label, BigDecimal base, BigDecimal ratePercent, BigDecimal amount) {
    }

    public record Result(UUID practitionerId, BigDecimal base, BigDecimal labDeduction, BigDecimal variable,
                         BigDecimal fixed, BigDecimal adjustment, BigDecimal gross, List<Line> lines,
                         Map<LocalDate, BigDecimal> fixedByDay) {
    }

    /** The rule in force for a practitioner on a day, if any. */
    public static Optional<Rule> ruleAt(List<Rule> rules, UUID practitionerId, LocalDate day) {
        return rules.stream().filter(r -> r.practitionerId().equals(practitionerId) && r.covers(day)).findFirst();
    }

    /**
     * Figures for every practitioner that has a rule touching the period. A
     * practitioner whose rules exist but whose items are all outside them still
     * appears, with zeros, so the screen can say so.
     */
    public static List<Result> compute(List<Rule> rules, List<Item> items, List<LabFee> labFees, LocalDate from, LocalDate to) {
        Set<UUID> practitioners = new LinkedHashSet<>();
        rules.stream().filter(r -> r.from().compareTo(to) <= 0 && (r.to() == null || r.to().compareTo(from) >= 0))
                .forEach(r -> practitioners.add(r.practitionerId()));
        List<Result> out = new ArrayList<>();
        for (UUID practitioner : practitioners) {
            out.add(computeOne(practitioner, rules, items, labFees, from, to));
        }
        return out;
    }

    private static Result computeOne(UUID practitioner, List<Rule> rules, List<Item> items, List<LabFee> labFees,
                                     LocalDate from, LocalDate to) {
        // Group items that fall under one rule into (day, invoice, category) so a
        // statement line is one thing a person can check against the invoice.
        record Key(LocalDate date, UUID invoice, String category, Rule rule) {
        }
        Map<Key, BigDecimal> grouped = new TreeMap<>(Comparator
                .comparing((Key k) -> k.date()).thenComparing(k -> String.valueOf(k.invoice())).thenComparing(Key::category));
        Map<Key, Item> sample = new HashMap<>();
        for (Item item : items) {
            if (!practitioner.equals(item.practitionerId()) || item.date().isBefore(from) || item.date().isAfter(to)) {
                continue;
            }
            Optional<Rule> rule = ruleAt(rules, practitioner, item.date());
            if (rule.isEmpty() || rule.get().basis() != item.basis()) {
                continue;
            }
            Key key = new Key(item.date(), item.invoiceId(), item.category() == null ? "" : item.category(), rule.get());
            grouped.merge(key, item.amount(), BigDecimal::add);
            sample.putIfAbsent(key, item);
        }

        List<Line> lines = new ArrayList<>();
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal variable = BigDecimal.ZERO;
        for (Map.Entry<Key, BigDecimal> e : grouped.entrySet()) {
            Item s = sample.get(e.getKey());
            BigDecimal lineBase = money(e.getValue());
            BigDecimal rate = e.getKey().rule().rateFor(e.getKey().category());
            BigDecimal amount = percent(lineBase, rate);
            lines.add(new Line(Kind.ITEM, e.getKey().date(), s.invoiceId(), s.invoiceNumber(), s.patientCode(),
                    e.getKey().category(), null, lineBase, rate, amount));
            base = base.add(lineBase);
            variable = variable.add(amount);
        }

        BigDecimal labDeduction = BigDecimal.ZERO;
        List<LabFee> mine = labFees.stream().filter(f -> practitioner.equals(f.practitionerId())
                && !f.date().isBefore(from) && !f.date().isAfter(to))
                .sorted(Comparator.comparing(LabFee::date).thenComparing(f -> String.valueOf(f.labOrderId()))).toList();
        for (LabFee fee : mine) {
            Optional<Rule> rule = ruleAt(rules, practitioner, fee.date());
            if (rule.isEmpty() || !rule.get().deductLabFees()) {
                continue;
            }
            BigDecimal amount = percent(money(fee.amount()), rule.get().ratePercent());
            lines.add(new Line(Kind.LAB, fee.date(), null, null, fee.patientCode(), null, fee.label(),
                    money(fee.amount()), rule.get().ratePercent(), amount.negate()));
            labDeduction = labDeduction.add(amount);
        }

        Map<LocalDate, BigDecimal> fixedByDay = fixedByDay(rules, practitioner, from, to);
        BigDecimal fixed = money(fixedByDay.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        if (fixed.signum() > 0) {
            lines.add(new Line(Kind.FIXED, to, null, null, null, null, "Fixed monthly amount", fixed, null, fixed));
        }

        BigDecimal raw = variable.subtract(labDeduction).add(fixed);
        BigDecimal adjustment = raw.signum() < 0 ? raw.negate() : BigDecimal.ZERO;
        if (adjustment.signum() > 0) {
            lines.add(new Line(Kind.ADJUSTMENT, to, null, null, null, null, "Lab fees exceed the amount due", null, null, adjustment));
        }
        return new Result(practitioner, base, labDeduction, variable, fixed, adjustment, raw.add(adjustment), lines, fixedByDay);
    }

    /**
     * The fixed amount accrued on each day of the period, unrounded: a month's
     * amount spread evenly over that month's days, so a full month adds up to
     * exactly the amount and a part month to the matching part.
     */
    public static Map<LocalDate, BigDecimal> fixedByDay(List<Rule> rules, UUID practitioner, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> out = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Optional<Rule> rule = ruleAt(rules, practitioner, day);
            if (rule.isPresent() && rule.get().fixedMonthly().signum() > 0) {
                out.put(day, rule.get().fixedMonthly().divide(BigDecimal.valueOf(day.lengthOfMonth()), WORKING_SCALE, RoundingMode.HALF_UP));
            }
        }
        return out;
    }

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal base, BigDecimal rate) {
        return base.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
