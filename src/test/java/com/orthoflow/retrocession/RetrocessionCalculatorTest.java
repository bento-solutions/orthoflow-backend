package com.orthoflow.retrocession;

import com.orthoflow.retrocession.application.service.RetrocessionCalculator;
import com.orthoflow.retrocession.application.service.RetrocessionCalculator.*;
import com.orthoflow.retrocession.domain.model.Basis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrocessionCalculatorTest {

    private static final UUID DOC = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Rule rule(Basis basis, String rate, boolean deductLab, String fixed, LocalDate from, LocalDate to, Map<String, BigDecimal> overrides) {
        return new Rule(UUID.randomUUID(), DOC, basis, d(rate), deductLab, d(fixed), from, to, overrides);
    }

    private static Rule collected(String rate) {
        return rule(Basis.COLLECTED, rate, false, "0", LocalDate.of(2026, 1, 1), null, Map.of());
    }

    private static Item item(Basis basis, String date, String category, String amount) {
        return new Item(basis, LocalDate.parse(date), DOC, UUID.randomUUID(), "INV-1", "P-001", category, d(amount));
    }

    private static Result only(List<Result> results) {
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @Test
    void paysAPercentageOfEachItem() {
        Result r = only(RetrocessionCalculator.compute(List.of(collected("30")),
                List.of(item(Basis.COLLECTED, "2026-03-05", "Bonding", "1000"), item(Basis.COLLECTED, "2026-03-20", "Bonding", "500")),
                List.of(), FROM, TO));
        assertThat(r.base()).isEqualByComparingTo("1500.00");
        assertThat(r.variable()).isEqualByComparingTo("450.00");
        assertThat(r.gross()).isEqualByComparingTo("450.00");
        assertThat(r.lines()).hasSize(2);
    }

    @Test
    void aCategoryOverrideReplacesTheStandardRate() {
        Rule rule = rule(Basis.COLLECTED, "30", false, "0", LocalDate.of(2026, 1, 1), null, Map.of("Retention", d("50")));
        Result r = only(RetrocessionCalculator.compute(List.of(rule),
                List.of(item(Basis.COLLECTED, "2026-03-05", "Bonding", "1000"), item(Basis.COLLECTED, "2026-03-06", "Retention", "200"),
                        item(Basis.COLLECTED, "2026-03-07", "", "100")), List.of(), FROM, TO));
        // 30% of 1000 + 50% of 200 + 30% (uncategorised uses the standard rate) of 100
        assertThat(r.variable()).isEqualByComparingTo("430.00");
    }

    @Test
    void theRateInForceOnTheItemsDateApplies() {
        Rule before = rule(Basis.COLLECTED, "30", false, "0", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 14), Map.of());
        Rule after = rule(Basis.COLLECTED, "40", false, "0", LocalDate.of(2026, 3, 15), null, Map.of());
        Result r = only(RetrocessionCalculator.compute(List.of(before, after),
                List.of(item(Basis.COLLECTED, "2026-03-14", "A", "1000"), item(Basis.COLLECTED, "2026-03-15", "A", "1000")),
                List.of(), FROM, TO));
        assertThat(r.variable()).isEqualByComparingTo("700.00");
    }

    @Test
    void itemsOfTheOtherBasisAreIgnored() {
        Result r = only(RetrocessionCalculator.compute(List.of(collected("30")),
                List.of(item(Basis.PRODUCED, "2026-03-05", "A", "1000")), List.of(), FROM, TO));
        assertThat(r.gross()).isEqualByComparingTo("0");
        assertThat(r.lines()).isEmpty();
    }

    @Test
    void itemsOutsideThePeriodOrForAnotherDoctorAreIgnored() {
        Item colleague = new Item(Basis.COLLECTED, LocalDate.parse("2026-03-05"), OTHER, UUID.randomUUID(), "INV-2", "P-002", "A", d("900"));
        Result r = only(RetrocessionCalculator.compute(List.of(collected("30")),
                List.of(item(Basis.COLLECTED, "2026-02-28", "A", "1000"), item(Basis.COLLECTED, "2026-04-01", "A", "1000"), colleague),
                List.of(), FROM, TO));
        assertThat(r.gross()).isEqualByComparingTo("0");
    }

    @Test
    void labFeesAreDeductedOnlyWhenTheRuleSaysSo() {
        LabFee fee = new LabFee(LocalDate.parse("2026-03-10"), DOC, UUID.randomUUID(), "Lab: ALIGNER", "P-001", d("200"));
        List<Item> items = List.of(item(Basis.COLLECTED, "2026-03-05", "A", "1000"));

        Result netted = only(RetrocessionCalculator.compute(
                List.of(rule(Basis.COLLECTED, "30", true, "0", LocalDate.of(2026, 1, 1), null, Map.of())), items, List.of(fee), FROM, TO));
        // 30% of 1000 = 300, minus 30% of the 200 lab bill = 60
        assertThat(netted.labDeduction()).isEqualByComparingTo("60.00");
        assertThat(netted.gross()).isEqualByComparingTo("240.00");

        Result untouched = only(RetrocessionCalculator.compute(List.of(collected("30")), items, List.of(fee), FROM, TO));
        assertThat(untouched.labDeduction()).isEqualByComparingTo("0");
        assertThat(untouched.gross()).isEqualByComparingTo("300.00");
    }

    @Test
    void aFullMonthOfFixedPayIsExactlyTheAmount() {
        Rule rule = rule(Basis.COLLECTED, "0", false, "3000", LocalDate.of(2026, 1, 1), null, Map.of());
        Result r = only(RetrocessionCalculator.compute(List.of(rule), List.of(), List.of(), FROM, TO));
        assertThat(r.fixed()).isEqualByComparingTo("3000.00");
        assertThat(r.gross()).isEqualByComparingTo("3000.00");
        assertThat(r.lines()).extracting(Line::kind).containsExactly(Kind.FIXED);
    }

    @Test
    void aPartMonthOfFixedPayIsProRatedByDays() {
        Rule rule = rule(Basis.COLLECTED, "0", false, "3100", LocalDate.of(2026, 1, 1), null, Map.of());
        // 10 of March's 31 days
        Result r = only(RetrocessionCalculator.compute(List.of(rule), List.of(), List.of(), FROM, LocalDate.of(2026, 3, 10)));
        assertThat(r.fixed()).isEqualByComparingTo("1000.00");
    }

    @Test
    void aPeriodSpanningTwoMonthsAddsEachMonthsShare() {
        Rule rule = rule(Basis.COLLECTED, "0", false, "2800", LocalDate.of(2026, 1, 1), null, Map.of());
        Result r = only(RetrocessionCalculator.compute(List.of(rule), List.of(), List.of(),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31)));
        assertThat(r.fixed()).isEqualByComparingTo("5600.00");
    }

    @Test
    void aShortfallBecomesAnExplicitAdjustmentNotANegativePayment() {
        LabFee fee = new LabFee(LocalDate.parse("2026-03-10"), DOC, UUID.randomUUID(), "Lab: CROWN", "P-001", d("1000"));
        Result r = only(RetrocessionCalculator.compute(
                List.of(rule(Basis.COLLECTED, "50", true, "0", LocalDate.of(2026, 1, 1), null, Map.of())),
                List.of(item(Basis.COLLECTED, "2026-03-05", "A", "200")), List.of(fee), FROM, TO));
        // 50% of 200 = 100, minus 50% of 1000 = 500 -> -400, floored to 0 with a +400 adjustment line
        assertThat(r.gross()).isEqualByComparingTo("0");
        assertThat(r.adjustment()).isEqualByComparingTo("400.00");
        assertThat(r.variable().subtract(r.labDeduction()).add(r.fixed()).add(r.adjustment())).isEqualByComparingTo(r.gross());
    }

    @Test
    void theLinesAlwaysAddUpToTheGrossTotal() {
        LabFee fee = new LabFee(LocalDate.parse("2026-03-10"), DOC, UUID.randomUUID(), "Lab: ALIGNER", "P-001", d("333.33"));
        Rule rule = rule(Basis.COLLECTED, "33.33", true, "1000", LocalDate.of(2026, 1, 1), null, Map.of("B", d("12.5")));
        Result r = only(RetrocessionCalculator.compute(List.of(rule),
                List.of(item(Basis.COLLECTED, "2026-03-05", "A", "777.77"), item(Basis.COLLECTED, "2026-03-06", "B", "123.45"),
                        item(Basis.COLLECTED, "2026-03-06", "A", "10.01")), List.of(fee), FROM, TO));
        BigDecimal sum = r.lines().stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(r.gross());
    }

    @Test
    void aPractitionerWithoutARuleInThePeriodIsNotListed() {
        Rule later = rule(Basis.COLLECTED, "30", false, "0", LocalDate.of(2026, 6, 1), null, Map.of());
        assertThat(RetrocessionCalculator.compute(List.of(later), List.of(item(Basis.COLLECTED, "2026-03-05", "A", "1000")), List.of(), FROM, TO)).isEmpty();
    }

    @Test
    void sameDayItemsOfOneInvoiceAndCategoryShareALine() {
        UUID invoice = UUID.randomUUID();
        Item a = new Item(Basis.COLLECTED, LocalDate.parse("2026-03-05"), DOC, invoice, "INV-9", "P-001", "A", d("100.004"));
        Item b = new Item(Basis.COLLECTED, LocalDate.parse("2026-03-05"), DOC, invoice, "INV-9", "P-001", "A", d("100.004"));
        Result r = only(RetrocessionCalculator.compute(List.of(collected("10")), List.of(a, b), List.of(), FROM, TO));
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).base()).isEqualByComparingTo("200.01");
    }
}
