package com.orthoflow.compliance.application.service;

import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.patient.domain.repository.PatientRepository;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.compliance.application.dto.PatientDataExport;
import com.orthoflow.consultation.application.service.ConsultationService;
import com.orthoflow.scheduling.domain.repository.AppointmentRepository;
import com.orthoflow.treatment.domain.repository.PatientTreatmentRepository;
import com.orthoflow.voice.application.service.VoiceAuditService;
import com.orthoflow.voice.application.service.VoiceSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Assembles the GDPR Art. 15/20 data-subject export by reading directly
 * from each module's own domain repository — the same cross-module
 * dependency pattern {@code AppointmentService} and {@code StockService}
 * already use to reference {@code billing.Patient}, applied here in the
 * read direction to aggregate rather than to attach a new record. This
 * intentionally lives in its own {@code compliance} module rather than
 * inside {@code billing} — the concern is genuinely cross-cutting (every
 * module that ever stores something against a patientId needs to be
 * reachable from here), and bolting it onto whichever module happens to
 * own {@code Patient} today would only entrench that module as a de facto
 * dumping ground further (see docs/adr/0001-patient-in-billing.md).
 */
@Service
@RequiredArgsConstructor
public class DataExportService {

    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientTreatmentRepository patientTreatmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final ClinicalRecordService clinicalRecordService;
    private final VoiceSessionService voiceSessionService;
    private final VoiceAuditService voiceAuditService;
    private final ConsultationService consultationService;

    @Transactional(readOnly = true)
    public PatientDataExport exportPatientData(UUID patientId) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));

        return PatientDataExport.builder()
                .exportedAt(OffsetDateTime.now())
                .patient(patient)
                .appointments(appointmentRepository.findByPatientId(patientId))
                .treatments(patientTreatmentRepository.findByPatientId(patientId))
                .invoices(invoiceRepository.findByPatientId(patientId))
                .clinicalRecord(clinicalRecordService.getRecord(patientId))
                .voiceSessions(voiceSessionService.listByPatient(patientId))
                .voiceCommands(voiceAuditService.forPatient(patientId))
                .consultations(consultationService.forExport(patientId))
                .build();
    }

    /**
     * Records that consent was captured — a deliberate, explicit action
     * distinct from the general patient-profile edit, so it shows up as its
     * own auditable event rather than being silently overwritable through
     * an unrelated "update contact details" form.
     */
    @Transactional
    public Patient recordConsent(UUID patientId, String notes) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));
        patient.setConsentGivenAt(OffsetDateTime.now());
        patient.setConsentNotes(notes);
        return patientRepository.save(patient);
    }
}
