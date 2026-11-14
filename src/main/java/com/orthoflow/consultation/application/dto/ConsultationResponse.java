package com.orthoflow.consultation.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A consultation as the browser sees it. {@code draft} and {@code reviewed} are
 * sent as JSON objects, not strings — they are structured data the browser reads.
 *
 * <p>{@code transcript} is null in a list (a patient's history of consultations
 * should not ship every conversation they ever had) and present when one is
 * opened.
 */
@Builder
public record ConsultationResponse(
        UUID id,
        UUID patientId,
        UUID actorId,
        UUID voiceSessionId,
        String status,
        /** {@code LIVE} or {@code DEFERRED}: whether commands acted while recording or once it ended. */
        String mode,
        String locale,
        OffsetDateTime patientInformedAt,
        String transcript,
        /** The same conversation line by line with the time of each; null in a list, like the transcript. */
        List<ConsultationLogResponse.Line> lines,
        JsonNode draft,
        /** The doctor's decisions on the panel so far; with the transcript only, null once saved. */
        JsonNode reviewState,
        JsonNode reviewed,
        String report,
        UUID appointmentId,
        /** The draft invoice the save made for the acts done during the consultation. */
        UUID invoiceId,
        /** The recording kept with the conversation, or null. */
        ConsultationAudioResponse audio,
        OffsetDateTime startedAt,
        OffsetDateTime examinationStartedAt,
        OffsetDateTime endedAt,
        OffsetDateTime completedAt) {}
