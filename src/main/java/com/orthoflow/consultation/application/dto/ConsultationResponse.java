package com.orthoflow.consultation.application.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.time.OffsetDateTime;
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
        String locale,
        OffsetDateTime patientInformedAt,
        String transcript,
        JsonNode draft,
        /** The doctor's decisions on the panel so far; with the transcript only, null once saved. */
        JsonNode reviewState,
        JsonNode reviewed,
        String report,
        UUID appointmentId,
        OffsetDateTime startedAt,
        OffsetDateTime examinationStartedAt,
        OffsetDateTime endedAt,
        OffsetDateTime completedAt) {}
