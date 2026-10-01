package com.orthoflow.consultation.application.dto;

import com.orthoflow.voice.application.dto.CommitVoiceSessionResponse;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * What saving did.
 *
 * <p>{@code saved} is false when a chart finding the doctor approved could not
 * be written. In that case <em>nothing else</em> was written either and the
 * consultation is still in review: saving again retries, and the doctor can
 * remove the failing finding. Telling them "saved" while a finding is missing
 * from the chart is the one outcome this must never produce.
 */
@Builder
public record CommitConsultationResponse(
        ConsultationResponse consultation,
        boolean saved,
        int executed,
        int rejected,
        int amended,
        int notReviewed,
        List<CommitVoiceSessionResponse.FailedCommand> failed,
        UUID appointmentId) {}
