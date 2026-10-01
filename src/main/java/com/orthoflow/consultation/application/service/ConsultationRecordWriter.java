package com.orthoflow.consultation.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orthoflow.clinical.application.dto.AddAllergyRequest;
import com.orthoflow.clinical.application.dto.AddMedicalHistoryRequest;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.clinical.domain.model.NoteCategory;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.consultation.application.dto.CommitConsultationRequest;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.patient.application.dto.PatientDemographicsUpdate;
import com.orthoflow.patient.application.service.PatientService;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.application.service.AppointmentService;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Everything a consultation save writes apart from the chart findings: who the
 * patient is, their allergies and history, the notes, the appointment.
 *
 * <p>One transaction, and it is its own bean so the transaction really applies
 * ({@link ConsultationCommitService} calls it from outside). The chart findings
 * are deliberately not in here — they are committed command by command through
 * the dictated-examination machinery, which is non-transactional by design and
 * runs first. Everything in here either lands together or not at all, so a
 * failed save leaves the record exactly as it was and saving again is a clean
 * retry rather than a repair.
 */
@Service
@RequiredArgsConstructor
class ConsultationRecordWriter {

    /** How rows this save creates are marked in the record's audit columns (max 20 chars). */
    static final String SOURCE = "consultation";

    private static final String DEFAULT_APPOINTMENT_TYPE = "Contrôle";

    private final ConsultationRepository consultations;
    private final PatientService patients;
    private final ClinicalRecordService clinical;
    private final AppointmentService appointments;
    private final ObjectMapper objectMapper;
    private final ConsultationExtractionProperties properties;

    @Transactional
    public Consultation write(UUID consultationId, CommitConsultationRequest request, UUID actorId) {
        Consultation consultation = consultations.findById(consultationId)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + consultationId));
        // Checked again inside the transaction: the caller read it a moment ago.
        if (consultation.getStatus() != ConsultationStatus.REVIEW) {
            throw new ValidationException("The consultation is " + consultation.getStatus() + " and cannot be saved.");
        }
        UUID patientId = consultation.getPatientId();
        // One id ties this consultation's notes together. The dictated-examination
        // session already files its report under its own id, so the rest join it.
        UUID noteSession = consultation.getVoiceSessionId() != null
                ? consultation.getVoiceSessionId() : consultation.getId();

        writePatient(patientId, request.getPatient());
        writeAllergies(patientId, noteSession, request, actorId);
        writeHistory(patientId, noteSession, request, actorId);
        writeNotes(patientId, noteSession, consultation, request, actorId);
        UUID appointmentId = writeAppointment(patientId, request.getNextAppointment());

        consultation.setStatus(ConsultationStatus.COMPLETED);
        consultation.setCompletedAt(OffsetDateTime.now());
        consultation.setReport(blankToNull(request.getReport()));
        consultation.setAppointmentId(appointmentId);
        consultation.setReviewed(reviewedSnapshot(request));
        // The panel's working state; what was signed off is `reviewed` now.
        consultation.setReviewState(null);
        if (!properties.isRetainTranscript()) {
            // Kept only so a crashed tab lost nothing; what the doctor validated
            // is the record now. The draft goes too: its quotes are the transcript.
            consultation.setTranscript(null);
            consultation.setDraft(null);
        }
        return consultations.save(consultation);
    }

    private void writePatient(UUID patientId, CommitConsultationRequest.PatientChanges changes) {
        if (changes == null) return;
        patients.applyDemographics(patientId, new PatientDemographicsUpdate(
                changes.getFirstName(), changes.getLastName(), changes.getDateOfBirth(), changes.getGender(),
                changes.getPhone(), changes.getCin(), changes.getInsuranceProvider(), changes.getInsuranceNumber()));
    }

    private void writeAllergies(UUID patientId, UUID noteSession, CommitConsultationRequest request, UUID actorId) {
        for (CommitConsultationRequest.AllergyItem item : request.getAllergies()) {
            AddAllergyRequest allergy = new AddAllergyRequest();
            allergy.setSubstance(item.getSubstance());
            allergy.setReaction(item.getReaction());
            allergy.setSeverity(item.getSeverity());
            allergy.setSource(SOURCE);
            allergy.setSessionId(noteSession);
            clinical.addAllergy(patientId, allergy, actorId);
        }
    }

    private void writeHistory(UUID patientId, UUID noteSession, CommitConsultationRequest request, UUID actorId) {
        for (CommitConsultationRequest.HistoryItem item : request.getMedicalHistory()) {
            clinical.addMedicalHistory(patientId,
                    history(item.getCategory(), item.getLabel(), item.getDetail(), noteSession), actorId);
        }
        // What the patient is going through now is history too, kept distinct by its detail.
        for (CommitConsultationRequest.ActiveTreatmentItem item : request.getActiveTreatments()) {
            String category = switch (item.getType() == null ? "OTHER" : item.getType()) {
                case "MEDICATION" -> "MEDICATION";
                case "DENTAL" -> "DENTAL_HISTORY";
                default -> "OTHER";
            };
            String detail = item.getDetail() == null || item.getDetail().isBlank()
                    ? "Traitement en cours" : "Traitement en cours — " + item.getDetail().trim();
            clinical.addMedicalHistory(patientId, history(category, item.getLabel(), detail, noteSession), actorId);
        }
    }

    private static AddMedicalHistoryRequest history(String category, String label, String detail, UUID session) {
        AddMedicalHistoryRequest entry = new AddMedicalHistoryRequest();
        entry.setCategory(category);
        entry.setLabel(label);
        entry.setDetail(detail);
        entry.setSource(SOURCE);
        entry.setSessionId(session);
        return entry;
    }

    private void writeNotes(UUID patientId, UUID noteSession, Consultation consultation,
                            CommitConsultationRequest request, UUID actorId) {
        note(patientId, noteSession, NoteCategory.CHIEF_COMPLAINT, request.getChiefComplaint(), actorId);
        note(patientId, noteSession, NoteCategory.TREATMENT_PLAN, planText(request), actorId);
        note(patientId, noteSession, NoteCategory.CONSULTATION_REPORT, request.getReport(), actorId);
        // The raw conversation, kept for later review — only where retention is
        // switched on, which is testing only (see ConsultationRetentionGuard).
        if (properties.isRetainTranscript()) {
            note(patientId, noteSession, NoteCategory.CONSULTATION_TRANSCRIPT, consultation.getTranscript(), actorId);
        }
    }

    private void note(UUID patientId, UUID session, NoteCategory category, String content, UUID actorId) {
        if (content == null || content.isBlank()) return;
        clinical.saveSessionNote(patientId, session, category, content, actorId, SOURCE);
    }

    /** The plan as a readable note, one line per act with its price. */
    static String planText(CommitConsultationRequest request) {
        if (request.getTreatmentPlan().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        BigDecimal total = BigDecimal.ZERO;
        boolean allPriced = true;
        for (CommitConsultationRequest.PlanLine line : request.getTreatmentPlan()) {
            int quantity = line.getQuantity() == null ? 1 : line.getQuantity();
            sb.append("• ").append(line.getLabel().trim());
            if (line.getTeeth() != null && !line.getTeeth().isBlank()) {
                sb.append(" (dent ").append(line.getTeeth().trim()).append(')');
            }
            if (quantity > 1) sb.append(" ×").append(quantity);
            if (line.getPrice() != null) {
                sb.append(" — ").append(line.getPrice().stripTrailingZeros().toPlainString()).append(" MAD");
                total = total.add(line.getPrice().multiply(BigDecimal.valueOf(quantity)));
            } else {
                allPriced = false;
            }
            if (line.getNotes() != null && !line.getNotes().isBlank()) sb.append(" — ").append(line.getNotes().trim());
            sb.append('\n');
        }
        if (total.signum() > 0) {
            sb.append(allPriced ? "Total : " : "Total partiel : ")
                    .append(total.stripTrailingZeros().toPlainString()).append(" MAD");
        }
        return sb.toString().trim();
    }

    private UUID writeAppointment(UUID patientId, CommitConsultationRequest.AppointmentSlot slot) {
        if (slot == null) return null;
        AppointmentRequest appointment = new AppointmentRequest();
        appointment.setPatientId(patientId);
        appointment.setDateTime(slot.getDateTime());
        appointment.setChairId(slot.getChairId());
        appointment.setDurationMinutes(slot.getDurationMinutes());
        appointment.setType(slot.getType() == null || slot.getType().isBlank() ? DEFAULT_APPOINTMENT_TYPE : slot.getType().trim());
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setNotes(slot.getNotes());
        AppointmentResponse saved = appointments.createAppointment(appointment);
        return saved.getId();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * What the doctor signed off, so the report and the assistant's sheet can be
     * printed again exactly as they were — without the chart ids, which are
     * plumbing and mean nothing outside the save.
     */
    private String reviewedSnapshot(CommitConsultationRequest request) {
        try {
            ObjectNode node = objectMapper.valueToTree(request);
            node.remove("approvedAuditIds");
            node.remove("rejectedAuditIds");
            node.remove("amendments");
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not serialise the reviewed consultation", e);
        }
    }
}
