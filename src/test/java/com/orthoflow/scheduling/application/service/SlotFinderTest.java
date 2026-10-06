package com.orthoflow.scheduling.application.service;

import com.orthoflow.scheduling.application.port.OpeningHoursProvider;
import com.orthoflow.scheduling.application.service.SlotFinder.Hold;
import com.orthoflow.scheduling.domain.model.*;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.*;
import com.orthoflow.team.application.dto.PractitionerDtos;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Specialty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Free time as the diary would show it. A Monday far enough ahead that the lead
 * time never interferes: the clinic is open 08:00-12:00 with a break 10:00-10:30.
 */
class SlotFinderTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    private static final LocalDate MONDAY = LocalDate.of(2030, 3, 4);

    private final UUID practice = UUID.randomUUID();
    private final UUID drA = UUID.randomUUID();
    private final UUID drB = UUID.randomUUID();

    private AppointmentJpaRepository appointments;
    private PractitionerAbsenceJpaRepository absences;
    private CalendarEventJpaRepository events;
    private ChairJpaRepository chairs;
    private PractitionerService practitioners;
    private SlotFinder finder;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentJpaRepository.class);
        absences = mock(PractitionerAbsenceJpaRepository.class);
        events = mock(CalendarEventJpaRepository.class);
        chairs = mock(ChairJpaRepository.class);
        practitioners = mock(PractitionerService.class);
        OpeningHoursProvider hours = (p, weekday) -> weekday == 7 ? Optional.empty()
                : Optional.of(new OpeningHoursProvider.Day(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(10, 0), LocalTime.of(10, 30)));
        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(absences.overlapping(any(), any(), any())).thenReturn(List.of());
        when(events.overlapping(any(), any(), any())).thenReturn(List.of());
        when(chairs.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(any())).thenReturn(List.of(new Chair(), new Chair()));
        finder = new SlotFinder(hours, appointments, absences, events, chairs, practitioners, id -> ZONE);
    }

    private void twoPractitioners() {
        when(practitioners.list(practice, false)).thenReturn(List.of(
                new PractitionerDtos.Response(drA, null, "A", "#111111", Specialty.ORTHODONTICS, null, 0, true),
                new PractitionerDtos.Response(drB, null, "B", "#222222", Specialty.ORTHODONTICS, null, 1, true)));
    }

    private static OffsetDateTime at(String time) {
        return MONDAY.atTime(LocalTime.parse(time)).atZone(ZONE).toOffsetDateTime();
    }

    private static Appointment booking(UUID practitioner, String start, int minutes) {
        return Appointment.builder().practitionerId(practitioner).dateTime(at(start)).durationMinutes(minutes).status(AppointmentStatus.SCHEDULED).build();
    }

    private List<String> times(UUID practitionerId, int minutes, List<Hold> holds) {
        return finder.freeSlots(practice, MONDAY, minutes, practitionerId, 0, 30, holds).stream()
                .map(t -> t.atZoneSameInstant(ZONE).toLocalTime().toString()).toList();
    }

    @Test
    void slotsRunAcrossTheOpeningHoursAndSkipTheBreak() {
        twoPractitioners();

        assertThat(times(drA, 30, List.of())).containsExactly("08:00", "08:30", "09:00", "09:30", "10:30", "11:00", "11:30");
    }

    @Test
    void aLongerVisitMustFitBeforeTheBreakAndBeforeClosing() {
        twoPractitioners();

        assertThat(times(drA, 60, List.of())).containsExactly("08:00", "08:30", "09:00", "10:30", "11:00");
    }

    @Test
    void aClosedDayHasNoSlots() {
        twoPractitioners();

        assertThat(finder.freeSlots(practice, LocalDate.of(2030, 3, 10), 30, drA, 0, 30, List.of())).isEmpty();
    }

    @Test
    void anAppointmentTakesTheSlotsItOverlapsForThatPractitionerOnly() {
        twoPractitioners();
        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(booking(drA, "08:30", 45)));

        assertThat(times(drA, 30, List.of())).doesNotContain("08:30", "09:00").contains("08:00", "09:30");
        assertThat(times(drB, 30, List.of())).contains("08:30", "09:00");
    }

    @Test
    void aCancelledAppointmentFreesItsSlot() {
        twoPractitioners();
        Appointment cancelled = booking(drA, "08:00", 30);
        cancelled.setStatus(AppointmentStatus.CANCELLED);
        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(cancelled));

        assertThat(times(drA, 30, List.of())).contains("08:00");
    }

    @Test
    void anAbsenceClosesThePractitionerAndNobodyElse() {
        twoPractitioners();
        when(absences.overlapping(any(), any(), any())).thenReturn(List.of(PractitionerAbsence.builder().practitionerId(drA)
                .startsAt(at("08:00")).endsAt(at("12:00")).build()));

        assertThat(times(drA, 30, List.of())).isEmpty();
        assertThat(times(drB, 30, List.of())).isNotEmpty();
        assertThat(times(null, 30, List.of())).isNotEmpty();
    }

    @Test
    void aClinicWideEventClosesEveryone() {
        twoPractitioners();
        when(events.overlapping(any(), any(), any())).thenReturn(List.of(CalendarEvent.builder().title("Réunion")
                .startsAt(at("08:00")).endsAt(at("09:00")).build()));

        assertThat(times(null, 30, List.of())).doesNotContain("08:00", "08:30").contains("09:00");
    }

    @Test
    void anEventOnAChairDoesNotCloseThePractitioner() {
        twoPractitioners();
        when(events.overlapping(any(), any(), any())).thenReturn(List.of(CalendarEvent.builder().title("Maintenance")
                .chairId(UUID.randomUUID()).startsAt(at("08:00")).endsAt(at("12:00")).build()));

        assertThat(times(drA, 30, List.of())).isNotEmpty();
    }

    @Test
    void withNoPreferenceASlotIsFreeIfAnyPractitionerIs() {
        twoPractitioners();
        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(booking(drA, "08:00", 30)));

        assertThat(times(null, 30, List.of())).contains("08:00");

        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(booking(drA, "08:00", 30), booking(drB, "08:00", 30)));
        assertThat(times(null, 30, List.of())).doesNotContain("08:00");
    }

    @Test
    void aPendingRequestHoldsItsSlotAndAnAnonymousOneUsesUpAPlace() {
        twoPractitioners();
        Hold forA = new Hold(drA, at("08:00"), at("08:30"));
        Hold anyone = new Hold(null, at("09:00"), at("09:30"));

        assertThat(times(drA, 30, List.of(forA))).doesNotContain("08:00");
        assertThat(times(drB, 30, List.of(forA))).contains("08:00");
        // Two practitioners, one place already promised to "anyone": still one free at 09:00 for a named doctor...
        assertThat(times(drA, 30, List.of(anyone))).doesNotContain("09:00");
        // ...but with both doctors booked, no slot is left for the anonymous request to take.
        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(booking(drA, "09:00", 30)));
        assertThat(times(null, 30, List.of(anyone))).doesNotContain("09:00");
    }

    @Test
    void theLeadTimeRulesOutSlotsThatAreTooSoon() {
        twoPractitioners();
        LocalDate today = LocalDate.now(ZONE);
        when(practitioners.list(practice, false)).thenReturn(List.of(new PractitionerDtos.Response(drA, null, "A", "#111", Specialty.ORTHODONTICS, null, 0, true)));
        // Open every weekday for this test.
        SlotFinder always = new SlotFinder((p, w) -> Optional.of(new OpeningHoursProvider.Day(LocalTime.of(0, 0), LocalTime.of(23, 30), null, null)),
                appointments, absences, events, chairs, practitioners, id -> ZONE);

        List<OffsetDateTime> slots = always.freeSlots(practice, today, 30, drA, 48, 30, List.of());

        assertThat(slots).isEmpty();
        assertThat(always.freeSlots(practice, today.plusDays(3), 30, drA, 48, 30, List.of())).isNotEmpty();
    }

    @Test
    void aClinicWithNoPractitionersIsLimitedByItsChairs() {
        when(practitioners.list(practice, false)).thenReturn(List.of());
        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(booking(null, "08:00", 30)));

        assertThat(times(null, 30, List.of())).contains("08:00");

        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(booking(null, "08:00", 30), booking(null, "08:00", 30)));
        assertThat(times(null, 30, List.of())).doesNotContain("08:00");
    }

    @Test
    void isFreeAnswersForASingleMoment() {
        twoPractitioners();
        when(appointments.agenda(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(booking(drA, "08:00", 30)));

        assertThat(finder.isFree(practice, at("08:00"), 30, drA, List.of())).isFalse();
        assertThat(finder.isFree(practice, at("08:00"), 30, drB, List.of())).isTrue();
        assertThat(finder.isFree(practice, at("10:00"), 30, drA, List.of())).isFalse();
    }
}
