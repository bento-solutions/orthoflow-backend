package com.orthoflow.consultation.application.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The recording of a consultation, as the browser plays it: {@code GET /consultations/{id}/audio}
 * serves the bytes. {@code startedAt} is the browser's clock when recording began, so a line's
 * {@code at} minus it is where that sentence is in the audio.
 */
public record ConsultationAudioResponse(UUID fileId, String contentType, long sizeBytes,
                                        OffsetDateTime startedAt, Long durationMs) {}
