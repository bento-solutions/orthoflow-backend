package com.orthoflow.consultation.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.consultation.application.dto.ConsultationConfigResponse;
import com.orthoflow.consultation.application.dto.ConsultationResponse;
import com.orthoflow.consultation.application.dto.ExtractionResponse;
import com.orthoflow.consultation.application.dto.StartConsultationRequest;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationDraft;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractor;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationPromptBuilder.CatalogEntry;
import com.orthoflow.consultation.infrastructure.extraction.RuleBasedExtractor;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import com.orthoflow.voice.application.dto.CompleteVoiceSessionRequest;
import com.orthoflow.voice.application.dto.StartVoiceSessionRequest;
import com.orthoflow.voice.application.dto.VoiceSessionResponse;
import com.orthoflow.voice.application.service.VoiceAuditService;
import com.orthoflow.voice.application.service.VoiceSessionService;
import com.orthoflow.voice.domain.model.CommandOutcome;
import com.orthoflow.voice.domain.model.VoiceSessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The life of a recorded consultation: start, read the conversation as it
 * goes, move from the conversation to the examination, stop, or throw away.
 * Saving is {@link ConsultationCommitService}'s.
 *
 * <p>Nothing here writes to the patient's record. The transcript and the
 * extraction are kept on the consultation row so a crashed browser loses
 * nothing; they become part of the record only when the doctor saves.
 *
 * <p>{@link #extract} is not transactional on purpose: it makes a call to a
 * model that can take twenty seconds, and holding a database transaction (and
 * its connection) open across that would starve the pool the first time a model
 * is slow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultationService {

    /** Far beyond any review (a few hundred items); a ceiling on what one PUT can store. */
    static final int MAX_REVIEW_STATE_CHARS = 300_000;

    private final ConsultationRepository consultations;
    private final VoiceSessionService voiceSessions;
    private final VoiceAuditService voiceAudit;
    private final PatientLookup patientLookup;
    private final TreatmentRepository treatments;
    private final ConsultationExtractor extractor;
    private final ConsultationExtractionProperties properties;
    private final ObjectMapper objectMapper;

    public ConsultationConfigResponse config() {
        return new ConsultationConfigResponse(properties.isEnabled(), properties.isEnabled() && extractor.isModelEnabled(),
                properties.isRetainTranscript());
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Transactional
    public ConsultationResponse start(StartConsultationRequest request, UUID actorId) {
        requireEnabled();
        if (!request.isPatientInformed()) {
            // Not a formality: Law 09-08 makes the patient's knowledge the lawful
            // basis for recording them, and this is the one place it can be required.
            throw new ValidationException("Confirm that the patient has been told the conversation is "
                    + "recorded and transcribed before recording starts.");
        }
        if (!patientLookup.exists(request.getPatientId())) {
            throw new NotFoundException("Patient not found: " + request.getPatientId());
        }
        consultations.findOpenByPatient(request.getPatientId()).ifPresent(open -> {
            throw new ConflictException("This patient already has an unfinished consultation. "
                    + "Resume it or discard it before starting another.");
        });

        StartVoiceSessionRequest session = new StartVoiceSessionRequest();
        session.setPatientId(request.getPatientId());
        session.setLocale(request.getLocale());
        VoiceSessionResponse voiceSession = voiceSessions.start(session, actorId);

        Consultation consultation = consultations.save(Consultation.builder()
                .patientId(request.getPatientId())
                .actorId(actorId)
                .voiceSessionId(voiceSession.id())
                .status(ConsultationStatus.INTAKE)
                .locale(request.getLocale())
                .patientInformedAt(OffsetDateTime.now())
                .transcript("")
                .build());
        return toResponse(consultation, true);
    }

    /**
     * Reads the conversation so far and says what it established. Persists the
     * transcript it was given, so the call that powers the side panel is also the
     * save that protects the conversation from a crash.
     */
    public ExtractionResponse extract(UUID id, String transcript) {
        requireEnabled();
        Consultation consultation = require(id);
        if (!consultation.getStatus().isOpen()) {
            throw new ValidationException("This consultation is closed.");
        }
        ConsultationExtractor.Extraction result = extractor.extract(transcript, catalog(), today());
        ConsultationDraft draft = remember(id, transcript, result);
        return new ExtractionResponse(draft, result.error(), result.truncated());
    }

    /** The doctor has called the consultation: the chart commands start to work. */
    @Transactional
    public ConsultationResponse beginExamination(UUID id) {
        requireEnabled();
        Consultation consultation = require(id);
        switch (consultation.getStatus()) {
            case INTAKE -> {
                consultation.setStatus(ConsultationStatus.EXAMINATION);
                consultation.setExaminationStartedAt(OffsetDateTime.now());
                consultation.touch();
                consultation = consultations.save(consultation);
            }
            case EXAMINATION -> { /* said twice; already there */ }
            default -> throw new ValidationException("The consultation is " + consultation.getStatus()
                    + " and cannot go back to the examination.");
        }
        return toResponse(consultation, true);
    }

    /** Recording stops; the doctor reviews. */
    @Transactional
    public ConsultationResponse end(UUID id, String transcript) {
        requireEnabled();
        Consultation consultation = require(id);
        consultation.touch();
        if (consultation.getStatus() == ConsultationStatus.REVIEW) {
            consultation.setTranscript(longer(consultation.getTranscript(), transcript));
            return toResponse(consultations.save(consultation), true);
        }
        if (!consultation.getStatus().isRecording()) {
            throw new ValidationException("The consultation is " + consultation.getStatus() + " and cannot be ended.");
        }
        consultation.setTranscript(longer(consultation.getTranscript(), transcript));
        consultation.setStatus(ConsultationStatus.REVIEW);
        consultation.setEndedAt(OffsetDateTime.now());

        // The dictated-examination session leaves ACTIVE with the consultation, so
        // a browser that ends only one of the two cannot strand the other.
        moveVoiceSession(consultation, VoiceSessionStatus.PENDING_REVIEW, VoiceSessionStatus.ACTIVE);
        return toResponse(consultations.save(consultation), true);
    }

    /**
     * Throws the consultation away. What was recorded is erased with it — a
     * conversation the doctor decided not to keep must not linger on the server
     * in a row nobody will ever open. Refused once a save has written chart
     * findings: those cannot be thrown away, so the consultation must be saved.
     */
    @Transactional
    public ConsultationResponse abandon(UUID id) {
        requireEnabled();
        return toResponse(discard(require(id)), false);
    }

    private Consultation discard(Consultation consultation) {
        if (consultation.getStatus() == ConsultationStatus.COMPLETED) {
            throw new ValidationException("A saved consultation cannot be discarded.");
        }
        if (consultation.getStatus() == ConsultationStatus.ABANDONED) {
            return consultation;
        }
        // Chart findings are written only by a save. Some written means a save
        // got halfway (one finding failed); discarding now would leave those in
        // the chart under a consultation that says nothing reached the record.
        long written = writtenChartFindings(consultation);
        if (written > 0) {
            throw new ConflictException(written + " chart finding(s) from this consultation are already in the "
                    + "patient's chart. Save the consultation to finish it: discarding would not remove them.");
        }
        consultation.setStatus(ConsultationStatus.ABANDONED);
        consultation.setEndedAt(OffsetDateTime.now());
        consultation.setTranscript(null);
        consultation.setDraft(null);
        consultation.setReviewState(null);
        moveVoiceSession(consultation, VoiceSessionStatus.ABANDONED,
                VoiceSessionStatus.ACTIVE, VoiceSessionStatus.PENDING_REVIEW);
        return consultations.save(consultation);
    }

    /**
     * Discards a consultation nobody has touched for too long — the scheduled
     * cleanup's. Not gated on the feature switch: a conversation recorded while
     * it was on must not outlive it.
     *
     * <p>One whose save already wrote chart findings cannot be discarded (see
     * {@link #abandon}); it stays open for the doctor to save, but what was said
     * goes: the transcript, the draft and the review state, whose quotes are the
     * transcript.
     *
     * @return true when it was discarded, false when only its text was erased
     */
    @Transactional
    public boolean discardIdle(UUID id) {
        Consultation consultation = require(id);
        if (!consultation.getStatus().isOpen()) return true;
        try {
            discard(consultation);
            return true;
        } catch (ConflictException partlySaved) {
            consultation.setTranscript(null);
            consultation.setDraft(null);
            consultation.setReviewState(null);
            consultations.save(consultation);
            return false;
        }
    }

    /**
     * Keeps what the doctor has decided on the panel so far, so a reload does
     * not undo it. Opaque to the server: the browser's own state, as JSON.
     */
    public void saveReviewState(UUID id, JsonNode state) {
        requireEnabled();
        require(id);
        if (state == null || !state.isObject()) {
            throw new ValidationException("The review state must be a JSON object.");
        }
        String json = toJson(state);
        if (json.length() > MAX_REVIEW_STATE_CHARS) {
            throw new ValidationException("The review state is too large.");
        }
        if (!consultations.saveReviewState(id, json)) {
            throw new ValidationException("This consultation is closed.");
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ConsultationResponse get(UUID id) {
        requireEnabled();
        return toResponse(require(id), true);
    }

    /** Newest first, without transcripts. */
    @Transactional(readOnly = true)
    public List<ConsultationResponse> listForPatient(UUID patientId) {
        requireEnabled();
        return consultations.findByPatient(patientId).stream()
                .filter(c -> c.getStatus() != ConsultationStatus.ABANDONED)
                .map(c -> toResponse(c, false))
                .toList();
    }

    /**
     * Everything held about this patient's consultations, transcripts included,
     * for their data export. Not gated on the feature switch: data recorded while
     * it was on is still theirs after it is turned off.
     */
    @Transactional(readOnly = true)
    public List<ConsultationResponse> forExport(UUID patientId) {
        return consultations.findByPatient(patientId).stream().map(c -> toResponse(c, true)).toList();
    }

    /** The one this patient has not finished, if any — offered back when their dossier opens. */
    @Transactional(readOnly = true)
    public ConsultationResponse findOpen(UUID patientId) {
        requireEnabled();
        return consultations.findOpenByPatient(patientId).map(c -> toResponse(c, true)).orElse(null);
    }

    // ── Internals ───────────────────────────────────────────────────────

    Consultation require(UUID id) {
        return consultations.findById(id)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + id));
    }

    void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new NotFoundException("Consultation recording is not enabled.");
        }
    }

    LocalDate today() {
        return LocalDate.now(ZoneId.of(properties.getTimezone()));
    }

    private List<CatalogEntry> catalog() {
        return treatments.findAll().stream()
                .filter(Treatment::isActive)
                .sorted(Comparator.comparing(Treatment::getName, String.CASE_INSENSITIVE_ORDER))
                .map(t -> new CatalogEntry(t.getId(), t.getCode(), t.getName(), t.getBasePrice()))
                .toList();
    }

    /**
     * Stores the transcript and draft on a fresh read of the row, and returns
     * the draft to show. The model call took seconds; in that time the doctor
     * may have ended or saved the consultation, and a late extraction must not
     * write over that.
     *
     * <p>When every model route failed (a rate limit, a timeout), the last
     * reading a model made stands rather than the rules' stand-in. The browser
     * drops every proposal a new draft no longer carries, so handing it the
     * rules' few fields would wipe the plan, the history and the appointment
     * off the panel — most often on the final reading, which sends the longest
     * transcript and is the likeliest to hit a rate limit.
     */
    private ConsultationDraft remember(UUID id, String transcript, ConsultationExtractor.Extraction result) {
        Consultation fresh = require(id);
        ConsultationDraft draft = result.draft();
        if (ConsultationExtractor.FAILED.equals(result.error())) {
            ConsultationDraft lastModelReading = readDraft(fresh.getDraft());
            if (lastModelReading != null && !RuleBasedExtractor.SOURCE.equals(lastModelReading.source())) {
                draft = lastModelReading;
            }
        }
        if (!fresh.getStatus().isOpen()) return draft;
        fresh.setTranscript(longer(fresh.getTranscript(), transcript));
        fresh.setDraft(toJson(draft));
        fresh.touch();
        consultations.save(fresh);
        return draft;
    }

    private ConsultationDraft readDraft(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, ConsultationDraft.class);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable draft on a consultation row; ignoring it");
            return null;
        }
    }

    /**
     * The newer text, except that an older, shorter one arriving late (a
     * retried request overtaken by the next) does not replace a longer one it is
     * the beginning of.
     */
    static String longer(String stored, String incoming) {
        String text = incoming == null ? "" : incoming;
        if (stored != null && stored.length() > text.length() && stored.startsWith(text)) return stored;
        return text;
    }

    private long writtenChartFindings(Consultation consultation) {
        if (consultation.getVoiceSessionId() == null) return 0;
        return voiceAudit.forSession(consultation.getVoiceSessionId()).stream()
                .filter(entry -> CommandOutcome.EXECUTED.name().equals(entry.outcome()))
                .count();
    }

    private void moveVoiceSession(Consultation consultation, VoiceSessionStatus target,
                                  VoiceSessionStatus... onlyFrom) {
        if (consultation.getVoiceSessionId() == null) return;
        try {
            VoiceSessionResponse current = voiceSessions.get(consultation.getVoiceSessionId());
            boolean movable = false;
            for (VoiceSessionStatus from : onlyFrom) {
                if (from.name().equals(current.status())) movable = true;
            }
            if (!movable) return;
            CompleteVoiceSessionRequest completion = new CompleteVoiceSessionRequest();
            completion.setStatus(target.name());
            completion.setConfirmed(false);
            voiceSessions.end(consultation.getVoiceSessionId(), completion, consultation.getActorId());
        } catch (NotFoundException e) {
            // The session is gone (an erasure); the consultation carries on without it.
            log.warn("Consultation {} lost its voice session", consultation.getId());
        }
    }

    ConsultationResponse toResponse(Consultation c, boolean withTranscript) {
        return ConsultationResponse.builder()
                .id(c.getId())
                .patientId(c.getPatientId())
                .actorId(c.getActorId())
                .voiceSessionId(c.getVoiceSessionId())
                .status(c.getStatus().name())
                .locale(c.getLocale())
                .patientInformedAt(c.getPatientInformedAt())
                .transcript(withTranscript ? c.getTranscript() : null)
                .draft(withTranscript ? readJson(c.getDraft()) : null)
                .reviewState(withTranscript ? readJson(c.getReviewState()) : null)
                .reviewed(readJson(c.getReviewed()))
                .report(c.getReport())
                .appointmentId(c.getAppointmentId())
                .startedAt(c.getStartedAt())
                .examinationStartedAt(c.getExaminationStartedAt())
                .endedAt(c.getEndedAt())
                .completedAt(c.getCompletedAt())
                .build();
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise consultation data", e);
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable JSON on a consultation row; omitting it");
            return null;
        }
    }
}
