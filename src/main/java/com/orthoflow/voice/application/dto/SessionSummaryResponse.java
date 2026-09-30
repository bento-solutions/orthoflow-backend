package com.orthoflow.voice.application.dto;

import lombok.Builder;

/**
 * The generated narrative for one dictated examination.
 *
 * <p>{@code error} is non-null when no summary could be produced — generation
 * is switched off, unconfigured, the provider was unreachable, or the session
 * recorded nothing. None of those are failures the doctor should be stopped
 * by: the review page renders the structured findings either way and they
 * write the observation themselves, so the client treats a non-null
 * {@code error} as "no narrative available" rather than as an error state.
 *
 * <p>Nothing here is persisted. The doctor edits this text at review and saves
 * it explicitly, which is the only path by which a generated sentence can
 * reach a clinical record.
 */
@Builder
public record SessionSummaryResponse(
        String summary,
        String provider,
        String model,
        /** How many audited commands the summary was built from. */
        int commandCount,
        /** True when the session was longer than the configured ceiling. */
        boolean truncated,
        /**
         * True for a model-written narrative that passed verification; false
         * for the structured report rendered from the records themselves,
         * which is what the review page gets when generation is off,
         * unreachable, or produced something that did not match the records.
         */
        boolean generated,
        String error
) {}
