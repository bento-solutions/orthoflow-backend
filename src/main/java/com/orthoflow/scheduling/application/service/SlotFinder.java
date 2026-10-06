package com.orthoflow.scheduling.application.service;

import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.scheduling.application.port.OpeningHoursProvider;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.CalendarEvent;
import com.orthoflow.scheduling.domain.model.PractitionerAbsence;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.CalendarEventJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.ChairJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.PractitionerAbsenceJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Free time, computed the way the diary would show it: opening hours for the
 * weekday, less the lunch break, less every appointment, absence and calendar
 * event that touches the person and the slot — and nothing sooner than the lead
 * time. Used by online booking, and by anything else that needs "when could this
 * go".
 *
 * <p>With no practitioner named, a slot is free if any practitioner is. A clinic
 * that has recorded no practitioners at all is limited by its chairs instead:
 * as many visits at once as it has chairs.
 */
@Service
@RequiredArgsConstructor
public class SlotFinder {

    /** A time already spoken for without being an appointment yet (a pending booking request). A null practitioner holds a place for anyone. */
    public record Hold(UUID practitionerId, OffsetDateTime start, OffsetDateTime end) {
    }

    private final OpeningHoursProvider openingHours;
    private final AppointmentJpaRepository appointments;
    private final PractitionerAbsenceJpaRepository absences;
    private final CalendarEventJpaRepository events;
    private final ChairJpaRepository chairs;
    private final PractitionerService practitionerService;
    private final PracticeZone practiceZone;

    @Transactional(readOnly = true)
    public List<OffsetDateTime> freeSlots(UUID practiceId, LocalDate day, int durationMinutes, UUID practitionerId,
                                          int leadTimeHours, int stepMinutes, List<Hold> holds) {
        ZoneId zone = practiceZone.of(practiceId);
        var hours = openingHours.forWeekday(practiceId, day.getDayOfWeek().getValue());
        if (hours.isEmpty()) {
            return List.of();
        }
        OffsetDateTime dayStart = day.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = dayStart.plusDays(1);
        OffsetDateTime earliest = OffsetDateTime.now().plusHours(leadTimeHours);

        List<UUID> resources = resources(practiceId, practitionerId);
        List<Appointment> booked = appointments.agenda(practiceId, dayStart, dayEnd, null, null, null, null).stream()
                .filter(a -> a.getStatus().holdsASlot()).toList();
        List<PractitionerAbsence> away = absences.overlapping(practiceId, dayStart, dayEnd);
        List<CalendarEvent> blocks = events.overlapping(practiceId, dayStart, dayEnd);
        int capacity = Math.max(1, chairs.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(practiceId).size());

        List<OffsetDateTime> free = new ArrayList<>();
        var day0 = hours.get();
        // Minutes since midnight, not LocalTime arithmetic: a LocalTime wraps past 23:59 back to 00:00, so a clinic
        // that closes at midnight would loop forever. Midnight itself (24:00) reads back from the database as the
        // last instant of the day and counts as 1440.
        int open = day0.open().toSecondOfDay() / 60;
        int close = minutes(day0.close());
        int breakStart = day0.breakStart() == null ? -1 : minutes(day0.breakStart());
        int breakEnd = day0.breakEnd() == null ? -1 : minutes(day0.breakEnd());
        for (int t = open; t + durationMinutes <= close; t += stepMinutes) {
            if (breakStart >= 0 && t < breakEnd && t + durationMinutes > breakStart) {
                continue;
            }
            OffsetDateTime start = day.atStartOfDay(zone).plusMinutes(t).toOffsetDateTime();
            OffsetDateTime stop = start.plusMinutes(durationMinutes);
            if (start.isBefore(earliest) || !isFree(resources, capacity, start, stop, booked, away, blocks, holds)) {
                continue;
            }
            free.add(start);
        }
        return free;
    }

    /** Whether a specific moment is still free: the last check before a request is accepted. */
    @Transactional(readOnly = true)
    public boolean isFree(UUID practiceId, OffsetDateTime start, int durationMinutes, UUID practitionerId, List<Hold> holds) {
        ZoneId zone = practiceZone.of(practiceId);
        LocalDate day = start.atZoneSameInstant(zone).toLocalDate();
        LocalTime time = start.atZoneSameInstant(zone).toLocalTime();
        return freeSlots(practiceId, day, durationMinutes, practitionerId, 0, 1, holds).stream()
                .anyMatch(s -> s.atZoneSameInstant(zone).toLocalTime().equals(time));
    }

    private static int minutes(LocalTime time) {
        return time.equals(LocalTime.MAX) || time.getHour() == 23 && time.getMinute() == 59 ? 24 * 60 : time.toSecondOfDay() / 60;
    }

    private List<UUID> resources(UUID practiceId, UUID practitionerId) {
        if (practitionerId != null) {
            return List.of(practitionerId);
        }
        List<UUID> active = practitionerService.list(practiceId, false).stream().map(p -> p.id()).toList();
        if (active.isEmpty()) {
            return java.util.Collections.singletonList(null);
        }
        return active;
    }

    private static boolean isFree(List<UUID> resources, int capacity, OffsetDateTime start, OffsetDateTime stop, List<Appointment> booked,
                                  List<PractitionerAbsence> away, List<CalendarEvent> blocks, List<Hold> holds) {
        boolean clinicClosed = blocks.stream().anyMatch(e -> e.getChairId() == null && e.getPractitionerId() == null && overlaps(e.getStartsAt(), e.getEndsAt(), start, stop));
        if (clinicClosed) {
            return false;
        }
        long anonymousHolds = holds.stream().filter(h -> h.practitionerId() == null && overlaps(h.start(), h.end(), start, stop)).count();
        if (resources.size() == 1 && resources.get(0) == null) {
            long busy = booked.stream().filter(a -> overlaps(a.getDateTime(), a.getDateTime().plusMinutes(a.getDurationMinutes()), start, stop)).count();
            return busy + anonymousHolds < capacity;
        }
        long freeResources = resources.stream().filter(r ->
                booked.stream().noneMatch(a -> r.equals(a.getPractitionerId()) && overlaps(a.getDateTime(), a.getDateTime().plusMinutes(a.getDurationMinutes()), start, stop))
                && away.stream().noneMatch(x -> r.equals(x.getPractitionerId()) && overlaps(x.getStartsAt(), x.getEndsAt(), start, stop))
                && blocks.stream().noneMatch(e -> r.equals(e.getPractitionerId()) && overlaps(e.getStartsAt(), e.getEndsAt(), start, stop))
                && holds.stream().noneMatch(h -> r.equals(h.practitionerId()) && overlaps(h.start(), h.end(), start, stop))).count();
        return freeResources - anonymousHolds > 0;
    }

    private static boolean overlaps(OffsetDateTime aStart, OffsetDateTime aEnd, OffsetDateTime bStart, OffsetDateTime bEnd) {
        return aStart.isBefore(bEnd) && aEnd.isAfter(bStart);
    }
}
