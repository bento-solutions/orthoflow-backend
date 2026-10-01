package com.orthoflow.consultation.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.consultation.application.dto.CommitConsultationRequest;
import com.orthoflow.consultation.application.dto.CommitConsultationResponse;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;
import com.orthoflow.voice.application.dto.CommitVoiceSessionRequest;
import com.orthoflow.voice.application.dto.CommitVoiceSessionResponse;
import com.orthoflow.voice.application.service.VoiceSessionCommitService;
import com.orthoflow.voice.domain.model.VoiceSession;
import com.orthoflow.voice.domain.model.VoiceSessionStatus;
import com.orthoflow.voice.domain.repository.VoiceSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Saves a reviewed consultation: the doctor's decisions become the patient's
 * record.
 *
 * <h2>Two steps, in this order, for a reason</h2>
 *
 * <p>First the chart findings dictated in the examination, through
 * {@link VoiceSessionCommitService} — the machinery that already guarantees
 * each finding is re-derived from what the server recorded and that a retry is
 * safe. It commits finding by finding, so it can half-succeed. Only if every
 * approved finding wrote does the second step run: everything else, in one
 * transaction ({@link ConsultationRecordWriter}).
 *
 * <p>The other order would let a consultation save its patient details,
 * allergies and appointment and then fail on a finding, leaving a record that
 * says "saved" in some tables and not others. This way a failure at either step
 * changes nothing the doctor cannot repeat: step one is idempotent per finding,
 * step two is atomic, and the consultation stays in review until both are done.
 *
 * <p>Saving takes the consultation first, through its version, so a double click
 * or a second tab gets a 409 instead of writing everything twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultationCommitService {

    private final ConsultationRepository consultations;
    private final ConsultationService consultationService;
    private final ConsultationRecordWriter writer;
    private final VoiceSessionCommitService voiceCommit;
    private final VoiceSessionRepository voiceSessions;

    public CommitConsultationResponse commit(UUID consultationId, CommitConsultationRequest request, UUID actorId) {
        consultationService.requireEnabled();
        Consultation consultation = consultations.findById(consultationId)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + consultationId));

        if (consultation.getStatus() == ConsultationStatus.COMPLETED) {
            throw new ValidationException("This consultation has already been saved.");
        }
        if (consultation.getStatus() == ConsultationStatus.ABANDONED) {
            throw new ValidationException("This consultation was discarded and cannot be saved.");
        }
        if (consultation.getStatus() != ConsultationStatus.REVIEW) {
            throw new ValidationException("End the recording before saving the consultation.");
        }

        // Take it. The write moves the version, so a concurrent save fails here
        // with an optimistic-lock conflict rather than after the clinical writes.
        consultation.setSaveStartedAt(OffsetDateTime.now());
        consultation = consultations.save(consultation);

        CommitVoiceSessionResponse chart = commitChart(consultation, request, actorId);
        if (chart != null && !chart.failed().isEmpty()) {
            log.warn("Consultation {} left in review: {} chart finding(s) did not write",
                    consultationId, chart.failed().size());
            return CommitConsultationResponse.builder()
                    .consultation(consultationService.toResponse(consultation, true))
                    .saved(false)
                    .executed(chart.executed())
                    .rejected(chart.rejected())
                    .amended(chart.amended())
                    .notReviewed(chart.notReviewed())
                    .failed(chart.failed())
                    .build();
        }

        Consultation saved = writer.write(consultationId, request, actorId);
        return CommitConsultationResponse.builder()
                .consultation(consultationService.toResponse(saved, true))
                .saved(true)
                .executed(chart == null ? 0 : chart.executed())
                .rejected(chart == null ? 0 : chart.rejected())
                .amended(chart == null ? 0 : chart.amended())
                .notReviewed(chart == null ? 0 : chart.notReviewed())
                .failed(List.of())
                .appointmentId(saved.getAppointmentId())
                .build();
    }

    /**
     * The chart findings, or null when this consultation has none to commit (no
     * session, or one a previous attempt already completed — step one succeeded
     * and step two did not, and running it again would be refused as "already
     * saved").
     */
    private CommitVoiceSessionResponse commitChart(Consultation consultation, CommitConsultationRequest request,
                                                   UUID actorId) {
        if (consultation.getVoiceSessionId() == null) return null;
        VoiceSession session = voiceSessions.findById(consultation.getVoiceSessionId()).orElse(null);
        if (session == null || session.getStatus() == VoiceSessionStatus.COMPLETED
                || session.getStatus() == VoiceSessionStatus.ABANDONED) {
            return null;
        }
        CommitVoiceSessionRequest chart = new CommitVoiceSessionRequest();
        chart.setApprovedAuditIds(request.getApprovedAuditIds());
        chart.setRejectedAuditIds(request.getRejectedAuditIds());
        chart.setAmendments(request.getAmendments());
        chart.setSummary(request.getReport());
        return voiceCommit.commit(session.getId(), chart, actorId);
    }
}
