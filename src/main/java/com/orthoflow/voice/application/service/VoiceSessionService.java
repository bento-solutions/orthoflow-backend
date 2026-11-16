package com.orthoflow.voice.application.service;

import com.orthoflow.patient.domain.repository.PatientRepository;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.voice.application.dto.CompleteVoiceSessionRequest;
import com.orthoflow.voice.application.dto.StartVoiceSessionRequest;
import com.orthoflow.voice.application.dto.VoiceSessionResponse;
import com.orthoflow.voice.domain.model.VoiceSession;
import com.orthoflow.voice.domain.model.VoiceSessionStatus;
import com.orthoflow.voice.domain.repository.VoiceSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Starts and ends a dictated examination.
 *
 * <p>Grouping commands into a session (migration V16 comment) is what lets a
 * whole consultation be reviewed and confirmed as one unit instead of
 * command by command; this service only owns the session's own lifecycle —
 * what happened *inside* it is the {@link VoiceCommandService}'s and
 * {@link com.orthoflow.clinical.application.service.ClinicalRecordService}'s
 * concern, addressed by {@code sessionId}.
 */
@Service
@RequiredArgsConstructor
public class VoiceSessionService {

    private final VoiceSessionRepository voiceSessionRepository;
    private final PatientRepository patientRepository;

    @Transactional
    public VoiceSessionResponse start(StartVoiceSessionRequest request, UUID actorId) {
        if (request.getPatientId() != null && patientRepository.findById(request.getPatientId()).isEmpty()) {
            throw new NotFoundException("Patient not found: " + request.getPatientId());
        }

        VoiceSession session = VoiceSession.builder()
                .patientId(request.getPatientId())
                .actorId(actorId)
                .status(VoiceSessionStatus.ACTIVE)
                .locale(request.getLocale())
                .build();

        return toResponse(voiceSessionRepository.save(session));
    }

    @Transactional
    public VoiceSessionResponse end(UUID sessionId, CompleteVoiceSessionRequest request, UUID actorId) {
        VoiceSession session = requireSession(sessionId);
        VoiceSessionStatus status = parseStatus(request.getStatus());

        session.setStatus(status);
        session.setEndedAt(OffsetDateTime.now());
        if (request.getSummary() != null && !request.getSummary().isBlank()) {
            session.setSummary(request.getSummary());
        }
        // Frozen at the moment the doctor confirms, per the entity's javadoc —
        // a later edit to a finding must not silently rewrite what was signed off.
        if (request.isConfirmed() && session.getConfirmedAt() == null) {
            session.setConfirmedAt(OffsetDateTime.now());
        }

        return toResponse(voiceSessionRepository.save(session));
    }

    /**
     * Dictation starts again on a session waiting for review — a recorded consultation the doctor
     * continues. What it staged stays staged; only a session still in review can go back.
     */
    @Transactional
    public VoiceSessionResponse reopen(UUID sessionId) {
        VoiceSession session = requireSession(sessionId);
        if (session.getStatus() == VoiceSessionStatus.ACTIVE) {
            return toResponse(session);
        }
        if (session.getStatus() != VoiceSessionStatus.PENDING_REVIEW) {
            throw new ValidationException("The voice session is " + session.getStatus() + " and cannot be reopened.");
        }
        session.setStatus(VoiceSessionStatus.ACTIVE);
        session.setEndedAt(null);
        return toResponse(voiceSessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public VoiceSessionResponse get(UUID sessionId) {
        return toResponse(requireSession(sessionId));
    }

    @Transactional(readOnly = true)
    public List<VoiceSessionResponse> listByPatient(UUID patientId) {
        return voiceSessionRepository.findByPatient(patientId).stream().map(this::toResponse).toList();
    }

    private VoiceSession requireSession(UUID sessionId) {
        return voiceSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Voice session not found: " + sessionId));
    }

    private VoiceSessionStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return VoiceSessionStatus.COMPLETED;
        try {
            return VoiceSessionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid voice session status: " + value);
        }
    }

    private VoiceSessionResponse toResponse(VoiceSession session) {
        return VoiceSessionResponse.builder()
                .id(session.getId())
                .patientId(session.getPatientId())
                .actorId(session.getActorId())
                .status(session.getStatus().name())
                .locale(session.getLocale())
                .summary(session.getSummary())
                .startedAt(session.getStartedAt())
                .endedAt(session.getEndedAt())
                .confirmedAt(session.getConfirmedAt())
                .build();
    }
}
