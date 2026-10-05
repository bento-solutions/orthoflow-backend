package com.orthoflow.lab;

import com.orthoflow.lab.application.service.LabOrderService;
import com.orthoflow.lab.domain.model.LabOrder;
import com.orthoflow.lab.domain.model.LabStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LabOrderRulesTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @SuppressWarnings("unchecked")
    private static List<String> warnings(LabOrder o, OffsetDateTime fittingAt) throws Exception {
        Method m = LabOrderService.class.getDeclaredMethod("warnings", LabOrder.class, OffsetDateTime.class, LocalDate.class, ZoneId.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, o, fittingAt, TODAY, ZONE);
    }

    private static LabOrder order(LabStatus status, LocalDate due) {
        return LabOrder.builder().status(status).dueDate(due).build();
    }

    private static OffsetDateTime at(String date) {
        return OffsetDateTime.parse(date + "T10:00:00+01:00");
    }

    @Test
    void aFittingBookedBeforeThePieceIsDueIsFlagged() throws Exception {
        assertThat(warnings(order(LabStatus.IN_PROGRESS, LocalDate.of(2026, 10, 20)), at("2026-10-15"))).containsExactly("FITTING_BEFORE_DUE");
    }

    @Test
    void aFittingDaysAwayWithNothingReceivedIsFlagged() throws Exception {
        assertThat(warnings(order(LabStatus.SENT, LocalDate.of(2026, 10, 1)), at("2026-10-06"))).contains("NOT_RECEIVED_BEFORE_FITTING");
    }

    @Test
    void anOverduePieceStillAtTheLabIsFlaggedButOnceReceivedItIsNot() throws Exception {
        assertThat(warnings(order(LabStatus.SENT, LocalDate.of(2026, 10, 1)), null)).containsExactly("OVERDUE");
        assertThat(warnings(order(LabStatus.RECEIVED, LocalDate.of(2026, 10, 1)), at("2026-10-06"))).isEmpty();
    }

    @Test
    void anOrderThatIsOnTimeWithTheFittingAfterTheDueDateRaisesNothing() throws Exception {
        assertThat(warnings(order(LabStatus.IN_PROGRESS, LocalDate.of(2026, 10, 20)), at("2026-10-28"))).isEmpty();
        assertThat(warnings(order(LabStatus.SENT, null), null)).isEmpty();
    }

    @Test
    void aPastFittingNeverRaisesTheArrivalWarning() throws Exception {
        assertThat(warnings(order(LabStatus.SENT, LocalDate.of(2026, 10, 1)), at("2026-10-03"))).doesNotContain("NOT_RECEIVED_BEFORE_FITTING");
    }

    @Test
    void anOrderMovesForwardOnlyAndARemakeGoesBackOut() {
        assertThat(LabStatus.SENT.next()).containsExactlyInAnyOrder(LabStatus.IN_PROGRESS, LabStatus.RECEIVED);
        assertThat(LabStatus.IN_PROGRESS.next()).containsExactly(LabStatus.RECEIVED);
        assertThat(LabStatus.RECEIVED.next()).containsExactlyInAnyOrder(LabStatus.FITTED, LabStatus.REMAKE);
        assertThat(LabStatus.FITTED.next()).containsExactly(LabStatus.REMAKE);
        assertThat(LabStatus.REMAKE.next()).containsExactlyInAnyOrder(LabStatus.SENT, LabStatus.IN_PROGRESS);
        assertThat(LabStatus.RECEIVED.next()).doesNotContain(LabStatus.SENT);
        assertThat(LabStatus.REMAKE.isOutstanding()).isTrue();
        assertThat(LabStatus.FITTED.isOutstanding()).isFalse();
    }
}
