package com.orthoflow.help.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;

public final class GuideDtos {

    private GuideDtos() {
    }

    /** The chapters of the first-login guide. */
    public enum GuideKey { FIRST_RUN_DASHBOARD, FIRST_RUN_DOSSIER, FIRST_RUN_VOICE }

    /**
     * Ordered: a chapter only moves up, so a stale tab cannot turn "completed" back into
     * "later". Snoozed is "later", dismissed is "don't show again".
     */
    public enum GuideStatus { SNOOZED, DISMISSED, COMPLETED }

    /** One chapter's progress; {@code version} is the version of the chapter's content the user saw. */
    public record GuideProgress(GuideKey key, GuideStatus status, int version, int snoozeCount,
                                String lastStep, OffsetDateTime updatedAt) {
    }

    public record SaveGuideProgress(@NotNull GuideStatus status,
                                    @NotNull @Min(1) @Max(1000) Integer version,
                                    @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String lastStep) {
    }
}
