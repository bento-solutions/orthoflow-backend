package com.orthoflow.scheduling.application.service;

import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientRegistrar;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.scheduling.application.dto.AgendaDtos.*;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import com.orthoflow.scheduling.domain.model.AppointmentType;
import com.orthoflow.scheduling.domain.model.Chair;
import com.orthoflow.scheduling.domain.model.WaitingRoom;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentTypeJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.ChairJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.WaitingRoomJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The reception desk's day: someone arrives, waits, is seated in a chair, is
 * done. Each step stamps the moment on the appointment (the raw material of
 * waiting-time and doctor-time figures), writes the activity log and tells open
 * screens, so every receptionist sees the same board.
 */
@Service
@RequiredArgsConstructor
public class FrontDeskService {

    private final AppointmentJpaRepository appointments;
    private final AppointmentService appointmentService;
    private final AppointmentTypeJpaRepository types;
    private final ChairJpaRepository chairs;
    private final WaitingRoomJpaRepository rooms;
    private final PatientLookup patientLookup;
    private final PatientRegistrar patientRegistrar;
    private final PractitionerService practitionerService;
    private final ActivityLog activityLog;
    private final LiveEventPublisher liveEvents;
    private final PracticeZone practiceZone;

    @Transactional
    public AppointmentResponse checkIn(UUID practiceId, UUID id, UUID waitingRoomId) {
        Appointment a = require(practiceId, id);
        if (!a.getStatus().isUpcoming()) {
            throw new ConflictException("Only a booked appointment can be checked in (it is " + a.getStatus() + ")");
        }
        a.setWaitingRoomId(resolveRoom(practiceId, waitingRoomId));
        a.setWaitingPriority(nextPriority(practiceId));
        return transition(a, AppointmentStatus.ARRIVED, "CHECKED_IN");
    }

    /** A patient with no booking: registers them if new, books a visit for now and puts them in the waiting room. */
    @Transactional
    public AppointmentResponse walkIn(UUID practiceId, WalkIn r) {
        UUID patientId = r.patientId();
        if (patientId == null) {
            if (blank(r.firstName()) || blank(r.lastName())) {
                throw new ValidationException("A walk-in needs an existing patient or a first and last name");
            }
            patientId = patientRegistrar.registerWalkIn(r.firstName(), r.lastName(), r.phone());
        }
        AppointmentRequest request = new AppointmentRequest();
        request.setPatientId(patientId);
        request.setDateTime(OffsetDateTime.now());
        request.setPractitionerId(r.practitionerId());
        request.setAppointmentTypeId(r.appointmentTypeId());
        request.setNotes(r.notes());
        request.setStatus(AppointmentStatus.ARRIVED);
        // The front desk is overruling the diary on purpose when someone is standing there.
        request.setIgnoreBlocks(true);
        if (r.appointmentTypeId() == null) {
            request.setType("Sans rendez-vous");
        }
        AppointmentResponse created = appointmentService.createAppointment(request, practiceId);
        Appointment a = require(practiceId, created.getId());
        a.setWaitingRoomId(resolveRoom(practiceId, r.waitingRoomId()));
        a.setWaitingPriority(nextPriority(practiceId));
        activityLog.record(practiceId, AppointmentService.ENTITY, a.getId(), "WALK_IN", null);
        liveEvents.publish(practiceId, "appointment", a.getId());
        return appointmentService.mapOne(appointments.save(a));
    }

    /** Calls a patient into a chair. The chair must be free: someone already in it is a conflict, not an overwrite. */
    @Transactional
    public AppointmentResponse seat(UUID practiceId, UUID id, UUID chairId) {
        Appointment a = require(practiceId, id);
        if (a.getStatus() == AppointmentStatus.IN_CHAIR || !a.getStatus().holdsASlot() || a.getStatus() == AppointmentStatus.COMPLETED) {
            throw new ConflictException("This appointment cannot be seated (it is " + a.getStatus() + ")");
        }
        Chair chair = chairs.findByIdAndPracticeId(chairId, practiceId).orElseThrow(() -> new NotFoundException("Chair not found"));
        if (!chair.isActive()) {
            throw new ConflictException("That chair is not in use");
        }
        appointments.inChair(practiceId).stream().filter(o -> chairId.equals(o.getChairId()) && !o.getId().equals(id)).findFirst()
                .ifPresent(o -> {
                    throw new ConflictException("That chair is occupied");
                });
        a.setChairId(chairId);
        return transition(a, AppointmentStatus.IN_CHAIR, "SEATED");
    }

    @Transactional
    public AppointmentResponse finish(UUID practiceId, UUID id) {
        Appointment a = require(practiceId, id);
        if (a.getStatus() != AppointmentStatus.IN_CHAIR && a.getStatus() != AppointmentStatus.ARRIVED) {
            throw new ConflictException("Only a patient who is here can be finished (it is " + a.getStatus() + ")");
        }
        return transition(a, AppointmentStatus.COMPLETED, "FINISHED");
    }

    /** Puts a patient who was called back in the waiting room, at the front of the queue. */
    @Transactional
    public AppointmentResponse backToWaiting(UUID practiceId, UUID id) {
        Appointment a = require(practiceId, id);
        if (a.getStatus() != AppointmentStatus.IN_CHAIR) {
            throw new ConflictException("Only a patient in a chair can be sent back to the waiting room");
        }
        a.setChairId(null);
        a.setWaitingPriority(-1);
        return transition(a, AppointmentStatus.ARRIVED, "BACK_TO_WAITING");
    }

    /** Drag-to-prioritise: the order of the ids is the order patients are called. */
    @Transactional
    public void reorder(UUID practiceId, List<UUID> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            Appointment a = require(practiceId, orderedIds.get(i));
            if (a.getStatus() == AppointmentStatus.ARRIVED) {
                a.setWaitingPriority(i);
            }
        }
        liveEvents.publish(practiceId, "appointment", null);
    }

    @Transactional(readOnly = true)
    public FrontDesk board(UUID practiceId) {
        ZoneId zone = practiceZone.of(practiceId);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime dayStart = now.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = dayStart.plusDays(1);

        List<Appointment> waiting = appointments.waiting(practiceId);
        List<Appointment> seated = appointments.inChair(practiceId);
        List<Appointment> arrivedToday = appointments.arrivedBetween(practiceId, dayStart, dayEnd);

        List<Appointment> everyone = new ArrayList<>(waiting);
        everyone.addAll(seated);
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(everyone.stream().map(Appointment::getPatientId).distinct().toList());
        Map<UUID, Practitioner> practitioners = practitionerService.byIds(everyone.stream().map(Appointment::getPractitionerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, AppointmentType> typeById = types.findAllById(waiting.stream().map(Appointment::getAppointmentTypeId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(AppointmentType::getId, t -> t));

        List<WaitingCard> cards = waiting.stream().map(a -> {
            PatientSummary p = patients.get(a.getPatientId());
            AppointmentType t = typeById.get(a.getAppointmentTypeId());
            Practitioner pr = practitioners.get(a.getPractitionerId());
            return new WaitingCard(a.getId(), a.getPatientId(), p == null ? null : p.fullName(), p == null ? null : p.phone(),
                    a.getType(), t == null ? null : t.getColor(), a.getPractitionerId(), pr == null ? null : pr.getDisplayName(),
                    a.getDateTime(), a.getArrivedAt(), minutes(a.getArrivedAt(), now), a.getWaitingPriority(), a.getWaitingRoomId(),
                    "Sans rendez-vous".equals(a.getType()));
        }).toList();

        Map<UUID, Appointment> byChair = seated.stream().filter(a -> a.getChairId() != null)
                .collect(Collectors.toMap(Appointment::getChairId, a -> a, (x, y) -> x));
        List<Chair> activeChairs = chairs.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(practiceId);
        List<ChairBoard> chairBoard = activeChairs.stream().map(c -> {
            Appointment a = byChair.get(c.getId());
            if (a == null) {
                return new ChairBoard(c.getId(), c.getName(), false, null, null, null, null, null);
            }
            PatientSummary p = patients.get(a.getPatientId());
            Practitioner pr = practitioners.get(a.getPractitionerId());
            return new ChairBoard(c.getId(), c.getName(), true, a.getId(), p == null ? null : p.fullName(),
                    pr == null ? null : pr.getDisplayName(), a.getSeatedAt(), minutes(a.getSeatedAt(), now));
        }).toList();

        List<Long> waits = arrivedToday.stream().map(a -> {
            OffsetDateTime end = a.getSeatedAt() != null ? a.getSeatedAt() : a.getStatus() == AppointmentStatus.ARRIVED ? now : null;
            return end == null || a.getArrivedAt() == null ? null : minutes(a.getArrivedAt(), end);
        }).filter(Objects::nonNull).toList();
        Double average = waits.isEmpty() ? null : Math.round(waits.stream().mapToLong(Long::longValue).average().orElse(0) * 10) / 10.0;
        Long longest = waits.stream().max(Long::compare).orElse(null);

        Kpis kpis = new Kpis(waiting.size(), seated.size(), arrivedToday.size(), average, longest, activeChairs.size(),
                (int) chairBoard.stream().filter(ChairBoard::occupied).count());
        return new FrontDesk(kpis, cards, chairBoard);
    }

    private AppointmentResponse transition(Appointment a, AppointmentStatus next, String action) {
        AppointmentStatus before = a.getStatus();
        appointmentService.stampStatus(a, next);
        Appointment saved = appointments.saveAndFlush(a);
        activityLog.record(saved.getPracticeId(), AppointmentService.ENTITY, saved.getId(), action,
                Map.of("status", Map.of("from", String.valueOf(before), "to", String.valueOf(next))));
        liveEvents.publish(saved.getPracticeId(), "appointment", saved.getId());
        return appointmentService.mapOne(saved);
    }

    private Appointment require(UUID practiceId, UUID id) {
        return appointments.findById(id).filter(a -> a.getPracticeId().equals(practiceId))
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
    }

    private UUID resolveRoom(UUID practiceId, UUID roomId) {
        if (roomId != null) {
            return rooms.findByIdAndPracticeId(roomId, practiceId).filter(WaitingRoom::isActive)
                    .orElseThrow(() -> new NotFoundException("Waiting room not found")).getId();
        }
        return rooms.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(practiceId).stream().findFirst()
                .map(WaitingRoom::getId).orElse(null);
    }

    private int nextPriority(UUID practiceId) {
        return appointments.waiting(practiceId).stream().mapToInt(Appointment::getWaitingPriority).max().orElse(-1) + 1;
    }

    private static long minutes(OffsetDateTime from, OffsetDateTime to) {
        return from == null ? 0 : Math.max(0, Duration.between(from, to).toMinutes());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
