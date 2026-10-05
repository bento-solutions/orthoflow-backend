package com.orthoflow.scheduling.application.service;

import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.scheduling.application.dto.AgendaDtos.*;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.domain.model.AppointmentType;
import com.orthoflow.scheduling.domain.model.WaitingListEntry;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentTypeJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.WaitingListJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * People who need a slot and have none. Most urgent first, then longest
 * waiting. Scheduling an entry books the appointment through the normal path —
 * so double-booking, absences and events are all enforced the same way — and
 * only then closes the entry.
 */
@Service
@RequiredArgsConstructor
public class WaitingListService {

    private final WaitingListJpaRepository entries;
    private final AppointmentTypeJpaRepository types;
    private final AppointmentService appointmentService;
    private final PatientLookup patientLookup;
    private final PractitionerService practitionerService;
    private final LiveEventPublisher liveEvents;

    @Transactional(readOnly = true)
    public List<WaitingEntryResponse> open(UUID practiceId) {
        List<WaitingListEntry> rows = entries.findByPracticeIdAndStatusOrderByCreatedAtAsc(practiceId, WaitingListEntry.Status.WAITING);
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(rows.stream().map(WaitingListEntry::getPatientId).distinct().toList());
        Map<UUID, AppointmentType> typeById = types.findAllById(rows.stream().map(WaitingListEntry::getAppointmentTypeId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(AppointmentType::getId, t -> t));
        Map<UUID, Practitioner> practitioners = practitionerService.byIds(rows.stream().map(WaitingListEntry::getPractitionerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream()
                .sorted(Comparator.comparing((WaitingListEntry e) -> e.getUrgency().ordinal()).reversed()
                        .thenComparing(WaitingListEntry::getCreatedAt))
                .map(e -> toResponse(e, patients.get(e.getPatientId()), typeById.get(e.getAppointmentTypeId()),
                        practitioners.get(e.getPractitionerId())))
                .toList();
    }

    @Transactional
    public WaitingEntryResponse create(UUID practiceId, UUID actorId, WaitingEntryRequest r) {
        if (!patientLookup.exists(r.patientId())) {
            throw new NotFoundException("Patient not found");
        }
        AppointmentType type = appointmentService.resolveType(practiceId, r.appointmentTypeId());
        if (r.practitionerId() != null) {
            practitionerService.require(practiceId, r.practitionerId());
        }
        WaitingListEntry entry = WaitingListEntry.builder().practiceId(practiceId).patientId(r.patientId())
                .appointmentTypeId(type == null ? null : type.getId()).practitionerId(r.practitionerId())
                .durationMinutes(r.durationMinutes() != null ? r.durationMinutes() : type != null ? type.getDefaultDurationMinutes() : 30)
                .preferredWeekdays(blankToNull(r.preferredWeekdays())).preferredFrom(r.preferredFrom()).preferredTo(r.preferredTo())
                .urgency(r.urgency() == null ? WaitingListEntry.Urgency.NORMAL : r.urgency()).notes(r.notes()).createdBy(actorId).build();
        liveEvents.publish(practiceId, "waiting-list", null);
        return one(entries.save(entry));
    }

    @Transactional
    public WaitingEntryResponse update(UUID practiceId, UUID id, WaitingEntryRequest r) {
        WaitingListEntry e = require(practiceId, id);
        AppointmentType type = appointmentService.resolveType(practiceId, r.appointmentTypeId());
        e.setAppointmentTypeId(type == null ? null : type.getId());
        e.setPractitionerId(r.practitionerId());
        if (r.durationMinutes() != null) e.setDurationMinutes(r.durationMinutes());
        e.setPreferredWeekdays(blankToNull(r.preferredWeekdays()));
        e.setPreferredFrom(r.preferredFrom());
        e.setPreferredTo(r.preferredTo());
        if (r.urgency() != null) e.setUrgency(r.urgency());
        e.setNotes(r.notes());
        liveEvents.publish(practiceId, "waiting-list", id);
        return one(entries.save(e));
    }

    @Transactional
    public void cancel(UUID practiceId, UUID id) {
        WaitingListEntry e = require(practiceId, id);
        e.setStatus(WaitingListEntry.Status.CANCELLED);
        liveEvents.publish(practiceId, "waiting-list", id);
    }

    /** Books the entry into a real slot. The appointment is created first, so a refused slot leaves the entry waiting. */
    @Transactional
    public AppointmentResponse schedule(UUID practiceId, UUID id, ScheduleFromWaiting r) {
        WaitingListEntry e = require(practiceId, id);
        if (e.getStatus() != WaitingListEntry.Status.WAITING) {
            throw new ConflictException("This entry is no longer waiting");
        }
        AppointmentRequest request = new AppointmentRequest();
        request.setPatientId(e.getPatientId());
        request.setDateTime(r.dateTime());
        request.setChairId(r.chairId());
        request.setPractitionerId(r.practitionerId() != null ? r.practitionerId() : e.getPractitionerId());
        request.setDurationMinutes(e.getDurationMinutes());
        request.setAppointmentTypeId(e.getAppointmentTypeId());
        request.setNotes(e.getNotes());
        request.setIgnoreBlocks(r.ignoreBlocks());
        if (e.getAppointmentTypeId() == null) {
            request.setType("Rendez-vous");
        }
        AppointmentResponse booked = appointmentService.createAppointment(request, practiceId);
        e.setStatus(WaitingListEntry.Status.SCHEDULED);
        e.setScheduledAppointmentId(booked.getId());
        liveEvents.publish(practiceId, "waiting-list", id);
        return booked;
    }

    private WaitingListEntry require(UUID practiceId, UUID id) {
        return entries.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Waiting list entry not found"));
    }

    private WaitingEntryResponse one(WaitingListEntry e) {
        Set<UUID> practitionerIds = e.getPractitionerId() == null ? Set.of() : Set.of(e.getPractitionerId());
        return toResponse(e, patientLookup.findSummary(e.getPatientId()).orElse(null),
                e.getAppointmentTypeId() == null ? null : types.findById(e.getAppointmentTypeId()).orElse(null),
                practitionerService.byIds(practitionerIds).get(e.getPractitionerId()));
    }

    private static WaitingEntryResponse toResponse(WaitingListEntry e, PatientSummary patient, AppointmentType type, Practitioner practitioner) {
        return new WaitingEntryResponse(e.getId(), e.getPatientId(), patient == null ? null : patient.fullName(),
                patient == null ? null : patient.phone(), e.getAppointmentTypeId(), type == null ? null : type.getNameFr(),
                type == null ? null : type.getColor(), e.getPractitionerId(), practitioner == null ? null : practitioner.getDisplayName(),
                e.getDurationMinutes(), e.getPreferredWeekdays(), e.getPreferredFrom(), e.getPreferredTo(), e.getUrgency(),
                e.getNotes(), e.getStatus(), e.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
