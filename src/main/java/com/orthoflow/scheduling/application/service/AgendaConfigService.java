package com.orthoflow.scheduling.application.service;

import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.scheduling.application.dto.AgendaDtos.*;
import com.orthoflow.scheduling.domain.model.*;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.*;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The agenda's reference data: appointment types, waiting rooms, chairs
 * (treatment rooms), practitioner absences and calendar events. All scoped to
 * the caller's clinic; each change tells open screens to refresh.
 */
@Service
@RequiredArgsConstructor
public class AgendaConfigService {

    private final AppointmentTypeJpaRepository types;
    private final WaitingRoomJpaRepository rooms;
    private final ChairJpaRepository chairs;
    private final PractitionerAbsenceJpaRepository absences;
    private final CalendarEventJpaRepository events;
    private final PractitionerService practitionerService;
    private final LiveEventPublisher liveEvents;

    // ── Appointment types ──────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<TypeResponse> types(UUID practiceId, boolean includeInactive) {
        var rows = includeInactive ? types.findByPracticeIdOrderByDisplayOrderAscNameFrAsc(practiceId)
                : types.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameFrAsc(practiceId);
        return rows.stream().map(TypeResponse::from).toList();
    }

    @Transactional
    public TypeResponse createType(UUID practiceId, TypeRequest r) {
        String code = r.code() != null && !r.code().isBlank() ? r.code() : uniqueCode(practiceId, r.nameEn());
        if (types.existsByPracticeIdAndCode(practiceId, code)) {
            throw new ConflictException("An appointment type with code " + code + " already exists");
        }
        AppointmentType t = AppointmentType.builder().practiceId(practiceId).code(code).build();
        apply(t, r);
        liveEvents.publish(practiceId, "agenda-config", null);
        return TypeResponse.from(types.save(t));
    }

    @Transactional
    public TypeResponse updateType(UUID practiceId, UUID id, TypeRequest r) {
        AppointmentType t = types.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Appointment type not found"));
        apply(t, r);
        liveEvents.publish(practiceId, "agenda-config", null);
        return TypeResponse.from(types.save(t));
    }

    private static void apply(AppointmentType t, TypeRequest r) {
        t.setNameFr(r.nameFr().trim());
        t.setNameEn(r.nameEn().trim());
        t.setNameAr(r.nameAr().trim());
        if (r.color() != null) t.setColor(r.color());
        if (r.defaultDurationMinutes() != null) t.setDefaultDurationMinutes(r.defaultDurationMinutes());
        if (r.bookableOnline() != null) t.setBookableOnline(r.bookableOnline());
        if (r.specialtyGroup() != null) t.setSpecialtyGroup(r.specialtyGroup());
        if (r.active() != null) t.setActive(r.active());
        if (r.displayOrder() != null) t.setDisplayOrder(r.displayOrder());
    }

    private String uniqueCode(UUID practiceId, String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        base = base.isEmpty() ? "TYPE" : base.substring(0, Math.min(base.length(), 30));
        String code = base;
        for (int i = 2; types.existsByPracticeIdAndCode(practiceId, code); i++) {
            code = base + "_" + i;
        }
        return code;
    }

    // ── Waiting rooms ──────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<RoomResponse> rooms(UUID practiceId, boolean includeInactive) {
        var rows = includeInactive ? rooms.findByPracticeIdOrderByDisplayOrderAscNameAsc(practiceId)
                : rooms.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(practiceId);
        return rows.stream().map(RoomResponse::from).toList();
    }

    @Transactional
    public RoomResponse createRoom(UUID practiceId, RoomRequest r) {
        WaitingRoom room = rooms.save(WaitingRoom.builder().practiceId(practiceId).name(r.name().trim())
                .active(r.active() == null || r.active()).displayOrder(r.displayOrder() == null ? 0 : r.displayOrder()).build());
        liveEvents.publish(practiceId, "agenda-config", null);
        return RoomResponse.from(room);
    }

    @Transactional
    public RoomResponse updateRoom(UUID practiceId, UUID id, RoomRequest r) {
        WaitingRoom room = rooms.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Waiting room not found"));
        room.setName(r.name().trim());
        if (r.active() != null) room.setActive(r.active());
        if (r.displayOrder() != null) room.setDisplayOrder(r.displayOrder());
        liveEvents.publish(practiceId, "agenda-config", null);
        return RoomResponse.from(rooms.save(room));
    }

    // ── Chairs (treatment rooms) ───────────────────────────────────────
    @Transactional(readOnly = true)
    public List<ChairDetail> chairs(UUID practiceId, boolean includeInactive) {
        var rows = includeInactive ? chairs.findByPracticeIdOrderByDisplayOrderAscNameAsc(practiceId)
                : chairs.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(practiceId);
        return rows.stream().map(ChairDetail::from).toList();
    }

    @Transactional
    public ChairDetail createChair(UUID practiceId, ChairRequest r) {
        Chair chair = chairs.save(Chair.builder().practiceId(practiceId).name(r.name().trim())
                .active(r.active() == null || r.active()).displayOrder(r.displayOrder() == null ? 0 : r.displayOrder()).build());
        liveEvents.publish(practiceId, "agenda-config", null);
        return ChairDetail.from(chair);
    }

    @Transactional
    public ChairDetail updateChair(UUID practiceId, UUID id, ChairRequest r) {
        Chair chair = chairs.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Chair not found"));
        chair.setName(r.name().trim());
        if (r.active() != null) chair.setActive(r.active());
        if (r.displayOrder() != null) chair.setDisplayOrder(r.displayOrder());
        liveEvents.publish(practiceId, "agenda-config", null);
        return ChairDetail.from(chairs.save(chair));
    }

    // ── Absences ───────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<AbsenceResponse> absences(UUID practiceId, OffsetDateTime from, OffsetDateTime to) {
        List<PractitionerAbsence> rows = absences.overlapping(practiceId, from, to);
        Map<UUID, Practitioner> names = practitionerService.byIds(rows.stream().map(PractitionerAbsence::getPractitionerId).collect(java.util.stream.Collectors.toSet()));
        return rows.stream().map(a -> toResponse(a, names.get(a.getPractitionerId()))).toList();
    }

    @Transactional
    public AbsenceResponse createAbsence(UUID practiceId, UUID actorId, AbsenceRequest r) {
        Practitioner practitioner = practitionerService.require(practiceId, r.practitionerId());
        assertOrder(r.startsAt(), r.endsAt());
        PractitionerAbsence saved = absences.save(PractitionerAbsence.builder().practiceId(practiceId)
                .practitionerId(r.practitionerId()).startsAt(r.startsAt()).endsAt(r.endsAt())
                .reason(r.reason() == null ? AbsenceReason.OTHER : r.reason()).notes(r.notes()).createdBy(actorId).build());
        liveEvents.publish(practiceId, "agenda-config", saved.getId());
        return toResponse(saved, practitioner);
    }

    @Transactional
    public AbsenceResponse updateAbsence(UUID practiceId, UUID id, AbsenceRequest r) {
        PractitionerAbsence a = absences.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Absence not found"));
        Practitioner practitioner = practitionerService.require(practiceId, r.practitionerId());
        assertOrder(r.startsAt(), r.endsAt());
        a.setPractitionerId(r.practitionerId());
        a.setStartsAt(r.startsAt());
        a.setEndsAt(r.endsAt());
        if (r.reason() != null) a.setReason(r.reason());
        a.setNotes(r.notes());
        liveEvents.publish(practiceId, "agenda-config", id);
        return toResponse(absences.save(a), practitioner);
    }

    @Transactional
    public void deleteAbsence(UUID practiceId, UUID id) {
        absences.findByIdAndPracticeId(id, practiceId).ifPresent(absences::delete);
        liveEvents.publish(practiceId, "agenda-config", id);
    }

    private static AbsenceResponse toResponse(PractitionerAbsence a, Practitioner p) {
        return new AbsenceResponse(a.getId(), a.getPractitionerId(), p == null ? null : p.getDisplayName(),
                a.getStartsAt(), a.getEndsAt(), a.getReason(), a.getNotes());
    }

    // ── Calendar events ────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<EventResponse> events(UUID practiceId, OffsetDateTime from, OffsetDateTime to) {
        return events.overlapping(practiceId, from, to).stream().map(EventResponse::from).toList();
    }

    @Transactional
    public EventResponse createEvent(UUID practiceId, UUID actorId, EventRequest r) {
        assertOrder(r.startsAt(), r.endsAt());
        validateTargets(practiceId, r);
        CalendarEvent e = events.save(CalendarEvent.builder().practiceId(practiceId).title(r.title().trim())
                .startsAt(r.startsAt()).endsAt(r.endsAt()).chairId(r.chairId()).practitionerId(r.practitionerId())
                .color(r.color() == null ? "#475569" : r.color()).notes(r.notes()).createdBy(actorId).build());
        liveEvents.publish(practiceId, "agenda-config", e.getId());
        return EventResponse.from(e);
    }

    @Transactional
    public EventResponse updateEvent(UUID practiceId, UUID id, EventRequest r) {
        CalendarEvent e = events.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Event not found"));
        assertOrder(r.startsAt(), r.endsAt());
        validateTargets(practiceId, r);
        e.setTitle(r.title().trim());
        e.setStartsAt(r.startsAt());
        e.setEndsAt(r.endsAt());
        e.setChairId(r.chairId());
        e.setPractitionerId(r.practitionerId());
        if (r.color() != null) e.setColor(r.color());
        e.setNotes(r.notes());
        liveEvents.publish(practiceId, "agenda-config", id);
        return EventResponse.from(events.save(e));
    }

    @Transactional
    public void deleteEvent(UUID practiceId, UUID id) {
        events.findByIdAndPracticeId(id, practiceId).ifPresent(events::delete);
        liveEvents.publish(practiceId, "agenda-config", id);
    }

    private void validateTargets(UUID practiceId, EventRequest r) {
        if (r.chairId() != null) {
            chairs.findByIdAndPracticeId(r.chairId(), practiceId).orElseThrow(() -> new NotFoundException("Chair not found"));
        }
        if (r.practitionerId() != null) {
            practitionerService.require(practiceId, r.practitionerId());
        }
    }

    private static void assertOrder(OffsetDateTime start, OffsetDateTime end) {
        if (!start.isBefore(end)) {
            throw new com.orthoflow.common.exception.ValidationException("The end must be after the start");
        }
    }
}
