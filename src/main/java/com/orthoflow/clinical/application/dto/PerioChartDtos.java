package com.orthoflow.clinical.application.dto;

import com.orthoflow.clinical.domain.perio.PerioSummary;
import com.orthoflow.clinical.domain.perio.ToothMeasurements;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** The wire shapes of the periodontal chart. */
public final class PerioChartDtos {

    private PerioChartDtos() {
    }

    /** One chart in the history of a patient: enough to pick it and see how it went, without its measurements. */
    public record ChartListItem(
            UUID id,
            LocalDate chartDate,
            String visitReason,
            long version,
            OffsetDateTime updatedAt,
            OffsetDateTime deletedAt,
            PerioSummary summary) {
    }

    /** A chart with all its measurements. {@code toothStatus} omits teeth that are present. */
    public record Chart(
            UUID id,
            UUID patientId,
            LocalDate chartDate,
            String visitReason,
            String clinicalNotes,
            Map<String, String> toothStatus,
            Map<String, ToothMeasurements> teeth,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            PerioSummary summary) {
    }

    /** Starts a chart. The date defaults to today; tooth statuses are carried over from the latest chart. */
    public record CreateChartRequest(
            LocalDate chartDate,
            @Size(max = 200) String visitReason) {
    }

    /**
     * The whole chart, as the clinician sees it. {@code expectedVersion} is the version the screen was
     * built on: a save from a stale screen is refused instead of overwriting what someone else charted.
     */
    public record SaveChartRequest(
            @NotNull Long expectedVersion,
            LocalDate chartDate,
            @Size(max = 200) String visitReason,
            @Size(max = 4000) String clinicalNotes,
            @NotNull Map<String, String> toothStatus,
            @NotNull Map<String, ToothMeasurements> teeth) {
    }
}
