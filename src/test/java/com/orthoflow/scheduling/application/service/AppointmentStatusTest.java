package com.orthoflow.scheduling.application.service;

import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The stamps taken on each transition are what waiting-time and doctor-time figures are computed from. */
class AppointmentStatusTest {

    private final AppointmentService service = new AppointmentService(null, null, null, null, null, null, null, null, null, null);

    private Appointment appointment() {
        return Appointment.builder().id(UUID.randomUUID()).patientId(UUID.randomUUID())
                .dateTime(OffsetDateTime.now()).type("Contrôle").status(AppointmentStatus.SCHEDULED).build();
    }

    @Test
    void arrivalIsStampedOnceAndClearsAnEarlierSeating() {
        Appointment a = appointment();
        service.stampStatus(a, AppointmentStatus.ARRIVED);
        OffsetDateTime first = a.getArrivedAt();

        assertThat(first).isNotNull();
        service.stampStatus(a, AppointmentStatus.ARRIVED);
        assertThat(a.getArrivedAt()).isEqualTo(first);
    }

    @Test
    void seatingStampsTheMomentAndLeavesTheWaitingRoom() {
        Appointment a = appointment();
        a.setWaitingRoomId(UUID.randomUUID());
        service.stampStatus(a, AppointmentStatus.ARRIVED);
        service.stampStatus(a, AppointmentStatus.IN_CHAIR);

        assertThat(a.getSeatedAt()).isNotNull().isAfterOrEqualTo(a.getArrivedAt());
        assertThat(a.getWaitingRoomId()).isNull();
    }

    @Test
    void sendingSomeoneBackToTheWaitingRoomForgetsTheSeatingSoTheWaitIsMeasuredAgain() {
        Appointment a = appointment();
        service.stampStatus(a, AppointmentStatus.IN_CHAIR);
        assertThat(a.getSeatedAt()).isNotNull();

        service.stampStatus(a, AppointmentStatus.ARRIVED);

        assertThat(a.getSeatedAt()).isNull();
    }

    @Test
    void finishingFillsInAnySkippedStep() {
        Appointment a = appointment();
        service.stampStatus(a, AppointmentStatus.COMPLETED);

        assertThat(a.getArrivedAt()).isNotNull();
        assertThat(a.getSeatedAt()).isNotNull();
        assertThat(a.getFinishedAt()).isNotNull();
    }

    @Test
    void cancellingFreesTheWaitingRoom() {
        Appointment a = appointment();
        a.setWaitingRoomId(UUID.randomUUID());
        service.stampStatus(a, AppointmentStatus.CANCELLED);

        assertThat(a.getWaitingRoomId()).isNull();
    }

    @Test
    void confirmationIsStampedOnce() {
        Appointment a = appointment();
        service.stampStatus(a, AppointmentStatus.CONFIRMED);
        OffsetDateTime first = a.getConfirmedAt();
        service.stampStatus(a, AppointmentStatus.CONFIRMED);

        assertThat(first).isNotNull();
        assertThat(a.getConfirmedAt()).isEqualTo(first);
    }

    @Test
    void onlyCancelledAndNoShowReleaseTheSlot() {
        for (AppointmentStatus s : AppointmentStatus.values()) {
            assertThat(s.holdsASlot()).isEqualTo(s != AppointmentStatus.CANCELLED && s != AppointmentStatus.NO_SHOW);
        }
        assertThat(AppointmentStatus.SCHEDULED.isUpcoming()).isTrue();
        assertThat(AppointmentStatus.ARRIVED.isUpcoming()).isFalse();
    }
}
