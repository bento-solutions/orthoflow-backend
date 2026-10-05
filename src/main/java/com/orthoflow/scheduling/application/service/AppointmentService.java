package com.orthoflow.scheduling.application.service;

import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.Chair;
import com.orthoflow.scheduling.domain.repository.AppointmentRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.ChairJpaRepository;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final int DEFAULT_DURATION_MINUTES = 30;

    private final AppointmentRepository appointmentRepository;
    private final PatientLookup patientLookup;
    private final ChairJpaRepository chairJpaRepository;
    private final PractitionerService practitionerService;

    @Transactional
    public AppointmentResponse createAppointment(AppointmentRequest request) {
        if (!patientLookup.exists(request.getPatientId())) {
            throw new NotFoundException("Patient not found");
        }

        Appointment appointment = Appointment.builder()
                .patientId(request.getPatientId())
                .dateTime(request.getDateTime())
                .chairId(request.getChairId())
                .practitionerId(request.getPractitionerId())
                .durationMinutes(request.getDurationMinutes() != null ? request.getDurationMinutes() : DEFAULT_DURATION_MINUTES)
                .type(request.getType())
                .status(request.getStatus())
                .notes(request.getNotes())
                .applianceStep(request.getApplianceStep())
                .build();

        // A concurrent request booking the same chair/overlapping window is
        // rejected by the database's exclusion constraint (V21), not caught
        // here — this save is where that DataIntegrityViolationException
        // surfaces, translated to a 409 by GlobalExceptionHandler.
        Appointment saved = appointmentRepository.save(appointment);
        return mapToResponse(saved, patientLookup.findSummary(saved.getPatientId()).orElse(null), chairName(saved.getChairId()),
                practitionerOf(saved.getPractitionerId()));
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

    @Transactional(readOnly = true)
    public AppointmentResponse getAppointmentById(UUID id) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        return mapToResponse(appointment, patientLookup.findSummary(appointment.getPatientId()).orElse(null), chairName(appointment.getChairId()),
                practitionerOf(appointment.getPractitionerId()));
    }

    @Transactional
    public AppointmentResponse updateAppointment(UUID id, AppointmentRequest request) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));

        if (request.getPatientId() != null && !request.getPatientId().equals(appointment.getPatientId())) {
            if (!patientLookup.exists(request.getPatientId())) {
                throw new NotFoundException("Patient not found");
            }
            appointment.setPatientId(request.getPatientId());
        }

        if (request.getDateTime() != null) appointment.setDateTime(request.getDateTime());
        if (request.getChairId() != null) appointment.setChairId(request.getChairId());
        if (request.getPractitionerId() != null) appointment.setPractitionerId(request.getPractitionerId());
        if (request.getDurationMinutes() != null) appointment.setDurationMinutes(request.getDurationMinutes());
        if (request.getType() != null) appointment.setType(request.getType());
        if (request.getStatus() != null) appointment.setStatus(request.getStatus());
        if (request.getNotes() != null) appointment.setNotes(request.getNotes());
        if (request.getApplianceStep() != null) appointment.setApplianceStep(request.getApplianceStep());

        Appointment updated = appointmentRepository.save(appointment);
        return mapToResponse(updated, patientLookup.findSummary(updated.getPatientId()).orElse(null), chairName(updated.getChairId()),
                practitionerOf(updated.getPractitionerId()));
    }

    @Transactional
    public void deleteAppointment(UUID id) {
        appointmentRepository.deleteById(id);
    }

    /** Batched patient + chair lookups so mapping a list of appointments stays two queries total, not two-per-row (audit II.9). */
    private List<AppointmentResponse> mapWithPatients(List<Appointment> appointments) {
        List<UUID> patientIds = appointments.stream().map(Appointment::getPatientId).distinct().toList();
        Map<UUID, PatientSummary> summaries = patientIds.isEmpty()
                ? Collections.emptyMap()
                : patientLookup.findSummaries(patientIds);

        List<UUID> chairIds = appointments.stream().map(Appointment::getChairId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> chairNames = chairIds.isEmpty()
                ? Collections.emptyMap()
                : chairJpaRepository.findAllById(chairIds).stream()
                        .collect(Collectors.toMap(Chair::getId, Chair::getName));

        Map<UUID, Practitioner> practitioners = practitionerService.byIds(appointments.stream()
                .map(Appointment::getPractitionerId).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));

        return appointments.stream()
                .map(a -> mapToResponse(a, summaries.get(a.getPatientId()), chairNames.get(a.getChairId()),
                        practitioners.get(a.getPractitionerId())))
                .collect(Collectors.toList());
    }

    private String chairName(UUID chairId) {
        if (chairId == null) return null;
        return chairJpaRepository.findById(chairId).map(Chair::getName).orElse(null);
    }

    private Practitioner practitionerOf(UUID practitionerId) {
        if (practitionerId == null) return null;
        return practitionerService.byIds(java.util.Set.of(practitionerId)).get(practitionerId);
    }

    private AppointmentResponse mapToResponse(Appointment appointment, PatientSummary patient, String chairName,
                                              Practitioner practitioner) {
        return AppointmentResponse.builder()
                .id(appointment.getId())
                .patientId(appointment.getPatientId())
                .patientName(patient != null ? patient.fullName() : null)
                .dateTime(appointment.getDateTime())
                .chairId(appointment.getChairId())
                .chairName(chairName)
                .practitionerId(appointment.getPractitionerId())
                .practitionerName(practitioner != null ? practitioner.getDisplayName() : null)
                .practitionerColor(practitioner != null ? practitioner.getColor() : null)
                .durationMinutes(appointment.getDurationMinutes())
                .type(appointment.getType())
                .status(appointment.getStatus())
                .notes(appointment.getNotes())
                .applianceStep(appointment.getApplianceStep())
                .createdAt(appointment.getCreatedAt())
                .updatedAt(appointment.getUpdatedAt())
                .build();
    }
}
