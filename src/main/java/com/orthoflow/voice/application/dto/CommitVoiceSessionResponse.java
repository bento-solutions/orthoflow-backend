package com.orthoflow.voice.application.dto;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Per-command outcome of a review commit.
 *
 * <p>Deliberately not all-or-nothing. One finding that fails to write — a
 * finding code the catalog no longer accepts, a tooth removed from the chart
 * since it was dictated — must not discard the rest of a consultation the
 * dentist just spent twenty minutes on. Each command succeeds or fails on its
 * own, the audit trail records which, and this tells the review page exactly
 * what to show as still outstanding.
 */
@Builder
public record CommitVoiceSessionResponse(
        VoiceSessionResponse session,
        int executed,
        int rejected,
        int amended,
        /**
         * Commands that were still pending when the examination was saved but
         * were on neither the approved nor the rejected list — dictated, never
         * reviewed, so not written. Non-zero means the review page did not show
         * everything that was dictated.
         */
        int notReviewed,
        /** Commands that were approved but could not be written. */
        List<FailedCommand> failed
) {
    @Builder
    public record FailedCommand(UUID auditId, String intent, String errorMessage) {}
}
