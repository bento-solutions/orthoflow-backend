package com.orthoflow.consultation.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the doctor opens to check a consultation after the fact: what was said
 * and when, every command the examination understood and what became of it, and
 * what the doctor finally validated.
 *
 * @param transcriptKept false when this deployment erases the conversation on
 *                       save: {@code lines} is then empty and the log is the
 *                       commands and the validated record only
 * @param lines          in the order they were said; {@code at} is null only on a
 *                       consultation recorded before lines were timed
 * @param commands       oldest first; the utterance is present only where
 *                       {@code orthoflow.voice.audit.store-transcript} keeps it
 * @param reviewed       what the doctor signed off, null while still open
 */
@Builder
public record ConsultationLogResponse(
        UUID consultationId,
        UUID patientId,
        UUID actorId,
        String status,
        String locale,
        OffsetDateTime patientInformedAt,
        OffsetDateTime startedAt,
        OffsetDateTime examinationStartedAt,
        OffsetDateTime endedAt,
        OffsetDateTime completedAt,
        boolean transcriptKept,
        List<Line> lines,
        List<VoiceCommandAuditResponse> commands,
        JsonNode reviewed,
        String report) {

    public record Line(int seq, OffsetDateTime at, String phase, boolean typed, String text) {}
}
