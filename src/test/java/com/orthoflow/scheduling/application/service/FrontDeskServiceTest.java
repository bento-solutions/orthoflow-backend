package com.orthoflow.scheduling.application.service;

import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientRegistrar;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.scheduling.application.dto.AgendaDtos.FrontDesk;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import com.orthoflow.scheduling.domain.model.Chair;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.*;
import com.orthoflow.team.application.service.PractitionerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrontDeskServiceTest {

    private final UUID practice = UUID.randomUUID();
    private final UUID chairA = UUID.randomUUID();
    private final UUID chairB = UUID.randomUUID();

    private AppointmentJpaRepository appointments;
    private ChairJpaRepository chairs;
    private PatientLookup patients;
    private FrontDeskService service;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentJpaRepository.class);
        chairs = mock(ChairJpaRepository.class);
        patients = mock(PatientLookup.class);
        PractitionerService practitioners = mock(PractitionerService.class);
        when(practitioners.byIds(any())).thenReturn(java.util.Collections.emptyMap());
        AppointmentService appointmentService = new AppointmentService(null, appointments, patients, chairs,
                mock(AppointmentTypeJpaRepository.class), practitioners, null, mock(ActivityLog.class),
                mock(LiveEventPublisher.class), null);
        AppointmentTypeJpaRepository types = mock(AppointmentTypeJpaRepository.class);
        when(types.findAllById(any())).thenReturn(List.of());
        PracticeZone zone = id -> ZoneId.of("Africa/Casablanca");
        service = new FrontDeskService(appointments, appointmentService, types, chairs, mock(WaitingRoomJpaRepository.class),
                patients, mock(PatientRegistrar.class), practitioners, mock(ActivityLog.class), mock(LiveEventPublisher.class), zone);
        when(appointments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(appointments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(patients.findSummaries(anyList())).thenReturn(Map.of());
        when(chairs.findAllById(any())).thenReturn(List.of());
        when(chairs.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(practice))
                .thenReturn(List.of(Chair.builder().id(chairA).name("Chair 1").build(), Chair.builder().id(chairB).name("Chair 2").build()));
    }

    private Appointment appointment(AppointmentStatus status) {
        Appointment a = Appointment.builder().id(UUID.randomUUID()).practiceId(practice).patientId(UUID.randomUUID())
                .dateTime(OffsetDateTime.now()).type("Contrôle").status(status).build();
        when(appointments.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void checkInOnlyAcceptsABookedAppointmentAndJoinsTheQueue() {
        Appointment booked = appointment(AppointmentStatus.SCHEDULED);
        when(appointments.waiting(practice)).thenReturn(List.of());

        service.checkIn(practice, booked.getId(), null);

        assertThat(booked.getStatus()).isEqualTo(AppointmentStatus.ARRIVED);
        assertThat(booked.getArrivedAt()).isNotNull();

        Appointment done = appointment(AppointmentStatus.COMPLETED);
        assertThatThrownBy(() -> service.checkIn(practice, done.getId(), null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aNewArrivalGoesToTheBackOfTheQueue() {
        Appointment first = appointment(AppointmentStatus.ARRIVED);
        first.setWaitingPriority(4);
        when(appointments.waiting(practice)).thenReturn(List.of(first));
        Appointment next = appointment(AppointmentStatus.CONFIRMED);

        service.checkIn(practice, next.getId(), null);

        assertThat(next.getWaitingPriority()).isEqualTo(5);
    }

    @Test
    void anOccupiedChairCannotBeTakenButAnotherCanBe() {
        Appointment inChair = appointment(AppointmentStatus.IN_CHAIR);
        inChair.setChairId(chairA);
        when(appointments.inChair(practice)).thenReturn(List.of(inChair));
        when(chairs.findByIdAndPracticeId(chairA, practice)).thenReturn(Optional.of(Chair.builder().id(chairA).name("Chair 1").active(true).build()));
        when(chairs.findByIdAndPracticeId(chairB, practice)).thenReturn(Optional.of(Chair.builder().id(chairB).name("Chair 2").active(true).build()));
        Appointment waiting = appointment(AppointmentStatus.ARRIVED);

        assertThatThrownBy(() -> service.seat(practice, waiting.getId(), chairA))
                .isInstanceOf(ConflictException.class).hasMessageContaining("occupied");

        service.seat(practice, waiting.getId(), chairB);

        assertThat(waiting.getStatus()).isEqualTo(AppointmentStatus.IN_CHAIR);
        assertThat(waiting.getChairId()).isEqualTo(chairB);
    }

    @Test
    void aChairOutOfServiceCannotBeUsed() {
        when(appointments.inChair(practice)).thenReturn(List.of());
        when(chairs.findByIdAndPracticeId(chairA, practice)).thenReturn(Optional.of(Chair.builder().id(chairA).name("Chair 1").active(false).build()));
        Appointment waiting = appointment(AppointmentStatus.ARRIVED);

        assertThatThrownBy(() -> service.seat(practice, waiting.getId(), chairA))
                .isInstanceOf(ConflictException.class).hasMessageContaining("not in use");
    }

    @Test
    void boardFiguresAverageAndLongestWait() {
        OffsetDateTime now = OffsetDateTime.now();
        Appointment waitingTenMinutes = appointment(AppointmentStatus.ARRIVED);
        waitingTenMinutes.setArrivedAt(now.minusMinutes(10));
        Appointment seatedAfterTwenty = appointment(AppointmentStatus.IN_CHAIR);
        seatedAfterTwenty.setArrivedAt(now.minusMinutes(50));
        seatedAfterTwenty.setSeatedAt(now.minusMinutes(30));
        seatedAfterTwenty.setChairId(chairA);
        when(appointments.waiting(practice)).thenReturn(List.of(waitingTenMinutes));
        when(appointments.inChair(practice)).thenReturn(List.of(seatedAfterTwenty));
        when(appointments.arrivedBetween(any(), any(), any())).thenReturn(List.of(waitingTenMinutes, seatedAfterTwenty));
        when(patients.findSummaries(anyList())).thenReturn(Map.of(
                waitingTenMinutes.getPatientId(), new PatientSummary(waitingTenMinutes.getPatientId(), "Sara", "Benziane", null, "0600", null)));

        FrontDesk board = service.board(practice);

        assertThat(board.kpis().waiting()).isEqualTo(1);
        assertThat(board.kpis().inTreatment()).isEqualTo(1);
        assertThat(board.kpis().averageWaitMinutes()).isEqualTo(15.0);
        assertThat(board.kpis().longestWaitMinutes()).isEqualTo(20);
        assertThat(board.kpis().chairsTotal()).isEqualTo(2);
        assertThat(board.kpis().chairsOccupied()).isEqualTo(1);
        assertThat(board.waiting().get(0).patientName()).isEqualTo("Sara Benziane");
        assertThat(board.waiting().get(0).waitMinutes()).isBetween(9L, 11L);
    }
}
