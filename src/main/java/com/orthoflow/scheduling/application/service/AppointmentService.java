package com.orthoflow.scheduling.application.service;

import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import com.orthoflow.scheduling.domain.model.AppointmentType;
import com.orthoflow.scheduling.domain.model.Chair;
import com.orthoflow.scheduling.domain.repository.AppointmentRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentTypeJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.ChairJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final int DEFAULT_DURATION_MINUTES = 30;
    static final String ENTITY = "APPOINTMENT";

    private final AppointmentRepository appointmentRepository;
    private final AppointmentJpaRepository appointments;
    private final PatientLookup patientLookup;
    private final ChairJpaRepository chairJpaRepository;
    private final AppointmentTypeJpaRepository types;
    private final PractitionerService practitionerService;
    private final SlotBlockChecker blocks;
    private final ActivityLog activityLog;
    private final LiveEventPublisher liveEvents;
    private final CurrentUserProvider currentUser;

    @Transactional
    public AppointmentResponse createAppointment(AppointmentRequest request) {
        return createAppointment(request, currentUser.requirePracticeId());
    }

    @Transactional
    public AppointmentResponse createAppointment(AppointmentRequest request, UUID practiceId) {
        if (!patientLookup.exists(request.getPatientId())) {
            throw new NotFoundException("Patient not found");
        }
        AppointmentType type = resolveType(practiceId, request.getAppointmentTypeId());
        if (request.getPractitionerId() != null) {
            practitionerService.require(practiceId, request.getPractitionerId());
        }
        String label = request.getType() != null && !request.getType().isBlank() ? request.getType().trim()
                : type != null ? type.getNameFr() : null;
        if (label == null) {
            throw new ValidationException("An appointment needs a type");
        }
        int duration = request.getDurationMinutes() != null ? request.getDurationMinutes()
                : type != null ? type.getDefaultDurationMinutes() : DEFAULT_DURATION_MINUTES;

        if (!Boolean.TRUE.equals(request.getIgnoreBlocks())) {
            blocks.assertFree(practiceId, request.getPractitionerId(), request.getChairId(),
                    request.getDateTime(), request.getDateTime().plusMinutes(duration));
        }

        Appointment appointment = Appointment.builder()
                .practiceId(practiceId)
                .patientId(request.getPatientId())
                .dateTime(request.getDateTime())
                .chairId(request.getChairId())
                .practitionerId(request.getPractitionerId())
                .durationMinutes(duration)
                .type(label)
                .appointmentTypeId(type != null ? type.getId() : null)
                .status(request.getStatus())
                .notes(request.getNotes())
                .applianceStep(request.getApplianceStep())
                .build();
        stampStatus(appointment, appointment.getStatus() == null ? AppointmentStatus.SCHEDULED : appointment.getStatus());

        // A concurrent request booking the same chair or practitioner in an
        // overlapping window is rejected by the database's exclusion
        // constraints (V21, V33), not caught here — the flush is where that
        // DataIntegrityViolationException surfaces, translated to a 409 by
        // GlobalExceptionHandler.
        Appointment saved = appointments.saveAndFlush(appointment);
        activityLog.record(practiceId, ENTITY, saved.getId(), "CREATED", snapshot(saved));
        liveEvents.publish(practiceId, "appointment", saved.getId());
        return mapOne(saved);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAllAppointments() {
        return mapWithPatients(appointmentRepository.findAll());
    }

    /**
     * Preferred over getAllAppointments for any screen that only needs a
     * bounded window (e.g. one day or month) — see AppointmentJpaRepository.
     */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAppointmentsInRange(OffsetDateTime start, OffsetDateTime end) {
        return mapWithPatients(appointmentRepository.findByDateTimeBetween(start, end));
    }

    /** The agenda: a window narrowed by any of practitioner, chair, status and type. The end is exclusive. */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> agenda(UUID practiceId, OffsetDateTime from, OffsetDateTime to, UUID practitionerId,
                                            UUID chairId, AppointmentStatus status, UUID typeId) {
        return mapWithPatients(appointments.agenda(practiceId, from, to, practitionerId, chairId, status, typeId));
    }

    @Transactional(readOnly = true)
    public AppointmentResponse getAppointmentById(UUID id) {
        return mapOne(appointmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found")));
    }

    @Transactional
    public AppointmentResponse updateAppointment(UUID id, AppointmentRequest request) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        UUID practiceId = appointment.getPracticeId();
        Map<String, Object> before = snapshot(appointment);

        if (request.getPatientId() != null && !request.getPatientId().equals(appointment.getPatientId())) {
            if (!patientLookup.exists(request.getPatientId())) {
                throw new NotFoundException("Patient not found");
            }
            appointment.setPatientId(request.getPatientId());
        }
        if (request.getAppointmentTypeId() != null) {
            AppointmentType type = resolveType(practiceId, request.getAppointmentTypeId());
            appointment.setAppointmentTypeId(type.getId());
            if (request.getType() == null || request.getType().isBlank()) {
                appointment.setType(type.getNameFr());
            }
        }
        if (request.getPractitionerId() != null) {
            practitionerService.require(practiceId, request.getPractitionerId());
            appointment.setPractitionerId(request.getPractitionerId());
        }
        boolean moved = request.getDateTime() != null || request.getChairId() != null
                || request.getDurationMinutes() != null || request.getPractitionerId() != null;
        if (request.getDateTime() != null) appointment.setDateTime(request.getDateTime());
        if (request.getChairId() != null) appointment.setChairId(request.getChairId());
        if (request.getDurationMinutes() != null) appointment.setDurationMinutes(request.getDurationMinutes());
        if (request.getType() != null && !request.getType().isBlank()) appointment.setType(request.getType());
        if (request.getNotes() != null) appointment.setNotes(request.getNotes());
        if (request.getApplianceStep() != null) appointment.setApplianceStep(request.getApplianceStep());
        if (request.getStatus() != null && request.getStatus() != appointment.getStatus()) {
            stampStatus(appointment, request.getStatus());
        }
        if (moved && appointment.getStatus().holdsASlot() && !Boolean.TRUE.equals(request.getIgnoreBlocks())) {
            blocks.assertFree(practiceId, appointment.getPractitionerId(), appointment.getChairId(),
                    appointment.getDateTime(), appointment.getDateTime().plusMinutes(appointment.getDurationMinutes()));
        }

        Appointment updated = appointments.saveAndFlush(appointment);
        Map<String, Object> changes = ActivityLog.diff(before, snapshot(updated));
        if (!changes.isEmpty()) {
            String action = changes.containsKey("status") && changes.size() == 1 ? "STATUS_CHANGED"
                    : changes.containsKey("dateTime") ? "RESCHEDULED" : "UPDATED";
            activityLog.record(practiceId, ENTITY, updated.getId(), action, changes);
        }
        liveEvents.publish(practiceId, "appointment", updated.getId());
        return mapOne(updated);
    }

    @Transactional
    public void deleteAppointment(UUID id) {
        Appointment existing = appointmentRepository.findById(id).orElse(null);
        appointmentRepository.deleteById(id);
        if (existing != null) {
            activityLog.record(existing.getPracticeId(), ENTITY, id, "DELETED", snapshot(existing));
            liveEvents.publish(existing.getPracticeId(), "appointment", id);
        }
    }

    /**
     * Moves an appointment to a new status and stamps the moment, which is the
     * raw material for waiting-time and doctor-time figures. The first arrival
     * wins: re-marking someone arrived does not move their queue position.
     */
    void stampStatus(Appointment a, AppointmentStatus next) {
        OffsetDateTime now = OffsetDateTime.now();
        a.setStatus(next);
        switch (next) {
            case CONFIRMED -> {
                if (a.getConfirmedAt() == null) a.setConfirmedAt(now);
            }
            case ARRIVED -> {
                if (a.getArrivedAt() == null) a.setArrivedAt(now);
                a.setSeatedAt(null);
            }
            case IN_CHAIR -> {
                if (a.getArrivedAt() == null) a.setArrivedAt(now);
                a.setSeatedAt(now);
                a.setWaitingRoomId(null);
            }
            case COMPLETED -> {
                if (a.getArrivedAt() == null) a.setArrivedAt(now);
                if (a.getSeatedAt() == null) a.setSeatedAt(now);
                a.setFinishedAt(now);
                a.setWaitingRoomId(null);
            }
            case CANCELLED, NO_SHOW -> a.setWaitingRoomId(null);
            default -> {
            }
        }
    }

    AppointmentType resolveType(UUID practiceId, UUID typeId) {
        if (typeId == null) {
            return null;
        }
        return types.findByIdAndPracticeId(typeId, practiceId)
                .orElseThrow(() -> new NotFoundException("Appointment type not found"));
    }

    static Map<String, Object> snapshot(Appointment a) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("dateTime", a.getDateTime());
        s.put("durationMinutes", a.getDurationMinutes());
        s.put("type", a.getType());
        s.put("status", a.getStatus());
        s.put("practitionerId", a.getPractitionerId());
        s.put("chairId", a.getChairId());
        s.put("patientId", a.getPatientId());
        s.put("notes", a.getNotes());
        return s;
    }

    AppointmentResponse mapOne(Appointment a) {
        return mapWithPatients(List.of(a)).get(0);
    }

    /** Batched patient, chair, practitioner and type lookups so mapping a list stays a handful of queries, not several per row (audit II.9). */
    List<AppointmentResponse> mapWithPatients(List<Appointment> list) {
        List<UUID> patientIds = list.stream().map(Appointment::getPatientId).distinct().toList();
        Map<UUID, PatientSummary> summaries = patientIds.isEmpty()
                ? Collections.emptyMap()
                : patientLookup.findSummaries(patientIds);

        List<UUID> chairIds = list.stream().map(Appointment::getChairId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> chairNames = chairIds.isEmpty()
                ? Collections.emptyMap()
                : chairJpaRepository.findAllById(chairIds).stream().collect(Collectors.toMap(Chair::getId, Chair::getName));

        Set<UUID> practitionerIds = list.stream().map(Appointment::getPractitionerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Practitioner> practitioners = practitionerService.byIds(practitionerIds);

        Set<UUID> typeIds = list.stream().map(Appointment::getAppointmentTypeId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, AppointmentType> typeById = typeIds.isEmpty() ? Collections.emptyMap()
                : types.findAllById(typeIds).stream().collect(Collectors.toMap(AppointmentType::getId, t -> t));

        return list.stream().map(a -> toResponse(a, summaries.get(a.getPatientId()), chairNames.get(a.getChairId()),
                practitioners.get(a.getPractitionerId()), typeById.get(a.getAppointmentTypeId()))).toList();
    }

    private AppointmentResponse toResponse(Appointment a, PatientSummary patient, String chairName,
                                           Practitioner practitioner, AppointmentType type) {
        return AppointmentResponse.builder()
                .id(a.getId())
                .patientId(a.getPatientId())
                .patientName(patient != null ? patient.fullName() : null)
                .patientPhone(patient != null ? patient.phone() : null)
                .dateTime(a.getDateTime())
                .chairId(a.getChairId())
                .chairName(chairName)
                .practitionerId(a.getPractitionerId())
                .practitionerName(practitioner != null ? practitioner.getDisplayName() : null)
                .practitionerColor(practitioner != null ? practitioner.getColor() : null)
                .durationMinutes(a.getDurationMinutes())
                .type(a.getType())
                .appointmentTypeId(a.getAppointmentTypeId())
                .typeColor(type != null ? type.getColor() : null)
                .status(a.getStatus())
                .notes(a.getNotes())
                .applianceStep(a.getApplianceStep())
                .confirmedAt(a.getConfirmedAt())
                .arrivedAt(a.getArrivedAt())
                .seatedAt(a.getSeatedAt())
                .finishedAt(a.getFinishedAt())
                .waitingRoomId(a.getWaitingRoomId())
                .waitingPriority(a.getWaitingPriority())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
