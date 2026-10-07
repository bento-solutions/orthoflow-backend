package com.orthoflow.reporting.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.SaveTaxSchedule;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.TaxBracket;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.TaxSchedule;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.TaxSimulation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The simulator does arithmetic on a schedule the clinic enters; no rates are built in. These use
 * invented round figures on purpose: they are not any year's real schedule.
 */
class TaxSimulationUnitTest {

    private static TaxBracket band(String upTo, String rate) {
        return new TaxBracket(upTo == null ? null : new BigDecimal(upTo), new BigDecimal(rate));
    }

    /** 0 % up to 10 000, 10 % up to 20 000, 20 % above; 100 off per dependent, two counted. */
    private static TaxSchedule schedule() {
        return new TaxSchedule(2026, true, "Invented figures for a test", List.of(band("10000", "0"), band("20000", "10"), band(null, "20")),
                new BigDecimal("100"), 2);
    }

    @Test
    void eachBandTaxesOnlyTheSliceOfIncomeThatFallsInIt() {
        TaxSimulation result = TaxSimulationService.compute(schedule(), new BigDecimal("30000"), 0);

        assertThat(result.bands()).hasSize(3);
        assertThat(result.bands().get(0).amountInBand()).isEqualByComparingTo("10000");
        assertThat(result.bands().get(0).tax()).isEqualByComparingTo("0");
        assertThat(result.bands().get(1).amountInBand()).isEqualByComparingTo("10000");
        assertThat(result.bands().get(1).tax()).isEqualByComparingTo("1000");
        assertThat(result.bands().get(2).amountInBand()).isEqualByComparingTo("10000");
        assertThat(result.bands().get(2).tax()).isEqualByComparingTo("2000");
        assertThat(result.grossTax()).isEqualByComparingTo("3000");
        assertThat(result.tax()).isEqualByComparingTo("3000");
        assertThat(result.effectiveRatePercent()).isEqualByComparingTo("10.00");
        assertThat(result.incomeAfterTax()).isEqualByComparingTo("27000");
        assertThat(result.source()).isEqualTo("Invented figures for a test");
    }

    @Test
    void anIncomeInsideTheFirstBandsOwesNothingInTheLaterOnes() {
        TaxSimulation result = TaxSimulationService.compute(schedule(), new BigDecimal("15000"), 0);

        assertThat(result.bands().get(1).amountInBand()).isEqualByComparingTo("5000");
        assertThat(result.bands().get(2).amountInBand()).isEqualByComparingTo("0");
        assertThat(result.tax()).isEqualByComparingTo("500");
    }

    @Test
    void dependentsReduceTheTaxUpToTheNumberCountedAndNeverBelowZero() {
        assertThat(TaxSimulationService.compute(schedule(), new BigDecimal("30000"), 1).tax()).isEqualByComparingTo("2900");
        // Three dependents, but only two are counted.
        assertThat(TaxSimulationService.compute(schedule(), new BigDecimal("30000"), 3).dependentRelief()).isEqualByComparingTo("200");
        // The relief cannot turn a small tax into a refund.
        TaxSimulation small = TaxSimulationService.compute(schedule(), new BigDecimal("10500"), 2);
        assertThat(small.grossTax()).isEqualByComparingTo("50");
        assertThat(small.dependentRelief()).isEqualByComparingTo("50");
        assertThat(small.tax()).isEqualByComparingTo("0");
    }

    @Test
    void aLossOwesNothingAndHasNoRate() {
        TaxSimulation loss = TaxSimulationService.compute(schedule(), new BigDecimal("-5000"), 0);

        assertThat(loss.tax()).isEqualByComparingTo("0");
        assertThat(loss.effectiveRatePercent()).isEqualByComparingTo("0");
        assertThat(loss.incomeAfterTax()).isEqualByComparingTo("-5000");
    }

    @Test
    void aBandsTaxIsRoundedToTheCentime() {
        TaxSchedule odd = new TaxSchedule(2026, true, "x", List.of(band(null, "33.33")), BigDecimal.ZERO, 0);

        assertThat(TaxSimulationService.compute(odd, new BigDecimal("100.02"), 0).tax()).isEqualByComparingTo("33.34");
    }

    // ── What a saved schedule must satisfy ──

    private static SaveTaxSchedule save(String source, List<TaxBracket> brackets) {
        return new SaveTaxSchedule(source, brackets, BigDecimal.ZERO, 0);
    }

    @Test
    void aScheduleMustSayWhereItsFiguresComeFrom() {
        List<TaxBracket> bands = List.of(band(null, "10"));
        assertThatThrownBy(() -> TaxSimulationService.validate(save(null, bands))).isInstanceOf(ValidationException.class).hasMessageContaining("where");
        assertThatThrownBy(() -> TaxSimulationService.validate(save("   ", bands))).isInstanceOf(ValidationException.class);
        TaxSimulationService.validate(save("Some text of law, 2026", bands));
    }

    @Test
    void theBandsMustClimbAndTheLastMustBeOpen() {
        assertThatThrownBy(() -> TaxSimulationService.validate(save("s", List.of(band("20000", "10"), band("10000", "20"), band(null, "30")))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("above the one before");
        assertThatThrownBy(() -> TaxSimulationService.validate(save("s", List.of(band("10000", "10"), band("10000", "20"), band(null, "30")))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TaxSimulationService.validate(save("s", List.of(band("10000", "10"), band("20000", "20")))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("last band");
        assertThatThrownBy(() -> TaxSimulationService.validate(save("s", List.of(band(null, "10"), band(null, "20")))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TaxSimulationService.validate(save("s", List.of())))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aRateMustBeAPercentage() {
        assertThatThrownBy(() -> TaxSimulationService.validate(save("s", List.of(band(null, "101")))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("rate");
        assertThatThrownBy(() -> TaxSimulationService.validate(save("s", List.of(band(null, "-1")))))
                .isInstanceOf(ValidationException.class);
        TaxSimulationService.validate(save("s", List.of(band(null, "0"))));
        TaxSimulationService.validate(save("s", List.of(band(null, "100"))));
    }

    @Test
    void theDependentDeductionCannotBeNegative() {
        assertThatThrownBy(() -> TaxSimulationService.validate(new SaveTaxSchedule("s", List.of(band(null, "10")), new BigDecimal("-1"), 2)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TaxSimulationService.validate(new SaveTaxSchedule("s", List.of(band(null, "10")), BigDecimal.ONE, 21)))
                .isInstanceOf(ValidationException.class);
    }
}
