package com.orthoflow.consultation.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.consultation.application.dto.ConsultationLogResponse;
import com.orthoflow.consultation.application.dto.TranscriptRequest;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.domain.model.ConsultationTranscriptLine;
import com.orthoflow.consultation.domain.model.ConsultationTranscriptLine.Phase;
import com.orthoflow.consultation.domain.repository.ConsultationLineRepository;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import com.orthoflow.voice.application.service.VoiceAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The consultation log: the conversation line by line with the time of each,
 * kept beside the commands the examination understood, so the doctor can check
 * afterwards what the proposals were read from and what they validated.
 *
 * <p>The log obeys the same retention switch as the block of text it sits
 * beside ({@code orthoflow.consultation.retain-transcript}): while a
 * consultation is open the lines are kept regardless, so a crashed tab loses
 * nothing; on save or discard they are erased unless retention is on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultationLogService {

    /** A browser clock this far ahead of the server's is not believed. */
    private static final long CLOCK_SKEW_TOLERANCE_MINUTES = 2;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConsultationRepository consultations;
    private final ConsultationLineRepository lines;
    private final VoiceAuditService voiceAudit;
    private final ConsultationExtractionProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Stores the lines not stored yet. Deliberately not one transaction around
     * the lot: two requests racing to store the same line would otherwise take
     * the caller's whole request down with a constraint violation, and a line
     * the other request already stored is exactly the one that failed to insert.
     */
    public void record(UUID consultationId, List<TranscriptRequest.Line> incoming) {
        requireEnabled();
        if (incoming == null || incoming.isEmpty()) return;
        Consultation consultation = require(consultationId);
        if (!consultation.getStatus().isOpen()) return;

        Set<Integer> known = new HashSet<>(lines.seqsOf(consultationId));
        OffsetDateTime now = OffsetDateTime.now();
        List<ConsultationTranscriptLine> fresh = new ArrayList<>();
        for (TranscriptRequest.Line line : incoming) {
            if (line.getText() == null || line.getText().isBlank() || !known.add(line.getSeq())) continue;
            fresh.add(ConsultationTranscriptLine.builder()
                    .consultationId(consultationId)
                    .seq(line.getSeq())
                    .spokenAt(believable(line.getAt(), consultation.getStartedAt(), now))
                    .recordedAt(now)
                    .phase(phaseOf(line.getPhase(), consultation))
                    .typed(line.isTyped())
                    .text(line.getText().trim())
                    .build());
        }
        if (fresh.isEmpty()) return;
        try {
            lines.saveAll(fresh);
        } catch (DataIntegrityViolationException raced) {
            // The same lines, stored a moment ago by the request this one overtook.
            log.debug("Consultation {}: lines already stored by a concurrent request", consultationId);
        }
    }

    /** A time the consultation could actually have had: not before it began, not in the future. */
    static OffsetDateTime believable(OffsetDateTime claimed, OffsetDateTime startedAt, OffsetDateTime now) {
        if (claimed == null) return now;
        if (startedAt != null && claimed.isBefore(startedAt)) return startedAt;
        OffsetDateTime latest = now.plusMinutes(CLOCK_SKEW_TOLERANCE_MINUTES);
        return claimed.isAfter(latest) ? now : claimed;
    }

    private static Phase phaseOf(String claimed, Consultation consultation) {
        if (claimed != null) {
            try {
                return Phase.valueOf(claimed.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the consultation's own phase
            }
        }
        return consultation.getStatus() == ConsultationStatus.INTAKE ? Phase.INTAKE : Phase.EXAMINATION;
    }

    // ── Reading ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ConsultationLogResponse log(UUID consultationId) {
        requireEnabled();
        Consultation c = require(consultationId);

        List<ConsultationLogResponse.Line> timed = linesOf(c);

        List<VoiceCommandAuditResponse> commands = c.getVoiceSessionId() == null ? List.of()
                : voiceAudit.forSession(c.getVoiceSessionId()).stream()
                        .sorted(Comparator.comparing(VoiceCommandAuditResponse::occurredAt))
                        .toList();

        return ConsultationLogResponse.builder()
                .consultationId(c.getId())
                .patientId(c.getPatientId())
                .actorId(c.getActorId())
                .status(c.getStatus().name())
                .locale(c.getLocale())
                .patientInformedAt(c.getPatientInformedAt())
                .startedAt(c.getStartedAt())
                .examinationStartedAt(c.getExaminationStartedAt())
                .endedAt(c.getEndedAt())
                .completedAt(c.getCompletedAt())
                .transcriptKept(!timed.isEmpty())
                .lines(timed)
                .commands(commands)
                .reviewed(readJson(c.getReviewed()))
                .report(c.getReport())
                .build();
    }

    /**
     * The lines as the browser holds them, so a consultation resumed after a crash
     * keeps their numbers and times: what was stored, or — for one recorded before
     * lines were timed — its text, without times.
     */
    @Transactional(readOnly = true)
    public List<ConsultationLogResponse.Line> lines(UUID consultationId) {
        return linesOf(require(consultationId));
    }

    private List<ConsultationLogResponse.Line> linesOf(Consultation c) {
        List<ConsultationLogResponse.Line> timed = lines.findByConsultation(c.getId()).stream()
                .map(l -> new ConsultationLogResponse.Line(l.getSeq(), l.getSpokenAt(), l.getPhase().name(),
                        l.isTyped(), l.getText()))
                .toList();
        return timed.isEmpty() ? untimed(c.getTranscript()) : timed;
    }

    /** A consultation recorded before lines were timed: its text, one line each, without times. */
    private static List<ConsultationLogResponse.Line> untimed(String transcript) {
        if (transcript == null || transcript.isBlank()) return List.of();
        List<ConsultationLogResponse.Line> out = new ArrayList<>();
        int seq = 0;
        for (String text : transcript.split("\n")) {
            if (!text.isBlank()) out.add(new ConsultationLogResponse.Line(seq++, null, null, false, text.trim()));
        }
        return out;
    }

    /**
     * The conversation as the note filed on the patient's record: one line each,
     * led by the clinic-time it was heard. Falls back to the plain text for a
     * consultation whose lines were never timed.
     */
    @Transactional(readOnly = true)
    public String timestampedTranscript(Consultation consultation) {
        List<ConsultationTranscriptLine> stored = lines.findByConsultation(consultation.getId());
        if (stored.isEmpty()) return consultation.getTranscript();
        ZoneId zone = ZoneId.of(properties.getTimezone());
        StringBuilder sb = new StringBuilder();
        for (ConsultationTranscriptLine line : stored) {
            if (sb.length() > 0) sb.append('\n');
            sb.append('[').append(CLOCK.format(line.getSpokenAt().atZoneSameInstant(zone))).append("] ");
            if (line.isTyped()) sb.append("(saisi) ");
            sb.append(line.getText());
        }
        return sb.toString();
    }

    // ── Erasing ─────────────────────────────────────────────────────────

    @Transactional
    public void erase(UUID consultationId) {
        lines.deleteByConsultation(consultationId);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new NotFoundException("Consultation recording is not enabled.");
        }
    }

    private Consultation require(UUID id) {
        return consultations.findById(id)
                .orElseThrow(() -> new NotFoundException("Consultation not found: " + id));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable JSON on a consultation row; leaving it out of the log");
            return null;
        }
    }
}
