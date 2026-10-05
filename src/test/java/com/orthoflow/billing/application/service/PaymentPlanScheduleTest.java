package com.orthoflow.billing.application.service;

import com.orthoflow.billing.application.service.PaymentPlanService.Slot;
import com.orthoflow.billing.domain.model.PaymentPlan.Frequency;
import com.orthoflow.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An ortho contract of 12 000 MAD with a down payment and monthly instalments must add up to the cent. */
class PaymentPlanScheduleTest {

    private static BigDecimal sum(List<Slot> slots) {
        return slots.stream().map(Slot::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void aDownPaymentFallsDueOnTheStartDateAndInstalmentsFollowMonthly() {
        List<Slot> slots = PaymentPlanService.schedule(new BigDecimal("12000"), new BigDecimal("3000"), 9, Frequency.MONTHLY, LocalDate.of(2026, 1, 15));

        assertThat(slots).hasSize(10);
        assertThat(slots.get(0).seq()).isZero();
        assertThat(slots.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(slots.get(0).amount()).isEqualByComparingTo("3000");
        assertThat(slots.get(1).dueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(slots.get(9).dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(slots.get(1).amount()).isEqualByComparingTo("1000.00");
        assertThat(sum(slots)).isEqualByComparingTo("12000.00");
    }

    @Test
    void theLastInstalmentAbsorbsCentsThatDoNotDivideEvenly() {
        List<Slot> slots = PaymentPlanService.schedule(new BigDecimal("1000.00"), null, 3, Frequency.MONTHLY, LocalDate.of(2026, 1, 1));

        assertThat(slots).extracting(Slot::amount).containsExactly(new BigDecimal("333.33"), new BigDecimal("333.33"), new BigDecimal("333.34"));
        assertThat(sum(slots)).isEqualByComparingTo("1000.00");
        assertThat(slots.get(0).seq()).isEqualTo(1);
    }

    @Test
    void monthEndDatesDoNotDriftBecauseEachIsMeasuredFromTheStart() {
        List<Slot> slots = PaymentPlanService.schedule(new BigDecimal("300"), null, 3, Frequency.MONTHLY, LocalDate.of(2026, 1, 31));

        assertThat(slots).extracting(Slot::dueDate).containsExactly(
                LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30));
    }

    @Test
    void weeklyBiweeklyAndQuarterlySpacing() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        assertThat(PaymentPlanService.schedule(new BigDecimal("100"), null, 2, Frequency.WEEKLY, start))
                .extracting(Slot::dueDate).containsExactly(start.plusWeeks(1), start.plusWeeks(2));
        assertThat(PaymentPlanService.schedule(new BigDecimal("100"), null, 2, Frequency.BIWEEKLY, start))
                .extracting(Slot::dueDate).containsExactly(start.plusWeeks(2), start.plusWeeks(4));
        assertThat(PaymentPlanService.schedule(new BigDecimal("100"), null, 2, Frequency.QUARTERLY, start))
                .extracting(Slot::dueDate).containsExactly(start.plusMonths(3), start.plusMonths(6));
    }

    @Test
    void aDownPaymentThatCoversEverythingIsRefused() {
        assertThatThrownBy(() -> PaymentPlanService.schedule(new BigDecimal("500"), new BigDecimal("500"), 3, Frequency.MONTHLY, LocalDate.now()))
                .isInstanceOf(ValidationException.class);
    }
}
