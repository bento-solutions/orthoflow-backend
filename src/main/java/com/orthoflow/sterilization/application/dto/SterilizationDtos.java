package com.orthoflow.sterilization.application.dto;

import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlResult;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlType;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SterilizationDtos {

    private SterilizationDtos() {
    }

    // ── Items ──
    /** {@code markSterile} registers an item already known to be sterile; otherwise it starts DIRTY and must pass a cycle. */
    public record ItemRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 150) String name, @NotNull Kind kind,
                              @Size(max = 60) String serialNumber, @Size(max = 2000) String notes, boolean markSterile) {
    }

    /** The code is not editable: it is printed on labels already stuck to trays. */
    public record ItemUpdate(@NotBlank @Size(max = 150) String name, @Size(max = 60) String serialNumber, @Size(max = 2000) String notes) {
    }

    public enum Action { USE, CLEAN, LUBRICATE, ADD_TO_CYCLE }

    public record ItemView(UUID id, String code, String name, Kind kind, State state, OffsetDateTime stateChangedAt, String serialNumber,
                           String notes, boolean active, OffsetDateTime lastUsedAt, OffsetDateTime lastLubricatedAt, boolean lubricationDue,
                           UUID lastCycleId, List<Action> nextActions) {
    }

    public record UseRequest(@NotNull UUID patientId, UUID appointmentId, @Size(max = 500) String note) {
    }

    public record LubricateRequest(@Size(max = 200) String product) {
    }

    public record RetireRequest(@Size(max = 500) String reason) {
    }

    // ── Autoclaves and cycles ──
    public record AutoclaveRequest(@NotBlank @Size(max = 100) String name, @Size(max = 100) String model,
                                   @Size(max = 60) String serialNumber, Boolean active) {
    }

    public record AutoclaveView(UUID id, String name, String model, String serialNumber, boolean active) {
    }

    public record CycleRequest(@NotNull UUID autoclaveId, @NotBlank @Size(max = 60) String program, OffsetDateTime startedAt,
                               OffsetDateTime finishedAt, ControlType controlType, @NotEmpty List<UUID> itemIds,
                               @Size(max = 2000) String notes) {
    }

    public record ControlRequest(@NotNull ControlResult result, @Size(max = 2000) String note) {
    }

    public record CycleSummary(UUID id, UUID autoclaveId, String autoclaveName, int number, String program, OffsetDateTime startedAt,
                               OffsetDateTime finishedAt, String operatorName, ControlType controlType, ControlResult controlResult,
                               OffsetDateTime controlledAt, int itemCount) {
    }

    /** {@code warnings} are things worth a second look that do not stop the cycle, such as a handpiece that was not lubricated. */
    public record CycleView(CycleSummary summary, String notes, String controlNote, List<ItemView> items, List<String> warnings) {
    }

    // ── Traceability ──
    public record TraceEntry(UUID eventId, OffsetDateTime at, String action, UUID itemId, String itemCode, String itemName, Kind kind,
                             UUID patientId, String patientName, String patientCode, UUID appointmentId, UUID cycleId,
                             Integer cycleNumber, String autoclaveName, String controlResult, String performedBy, String note) {
    }

    /** What a failed cycle puts at stake: where its items are now, and everyone they touched since it. */
    public record Exposure(CycleSummary cycle, List<ItemView> items, List<TraceEntry> uses) {
    }

    // ── Dashboard ──
    @io.swagger.v3.oas.annotations.media.Schema(name = "SterilizationDashboard")
    public record Dashboard(Map<State, Long> counts, List<ItemView> lubricationDue, List<ItemView> shelfLifeExceeded,
                            List<CycleSummary> pendingControls, List<EndoDtos.EndoAlert> endoAlerts, int shelfLifeDays) {
    }

    // ── Endo ──
    public static final class EndoDtos {

        private EndoDtos() {
        }

        public record ModelRequest(@NotBlank @Size(max = 100) String name, @Size(max = 80) String brand, @Size(max = 40) String sizeTaper,
                                   @Min(1) @Max(1000) int maxUses, Boolean active) {
        }

        public record ModelView(UUID id, String name, String brand, String sizeTaper, int maxUses, boolean active) {
        }

        public record KitFileRequest(@NotNull UUID modelId, @Min(1) @Max(20) int quantity) {
        }

        public record DiscardRequest(@Size(max = 120) String reason) {
        }

        public record FileView(UUID id, UUID modelId, String modelName, int useCount, int maxUses, int remaining, boolean atLimit,
                               boolean nearLimit) {
        }

        public record KitView(ItemView item, List<FileView> files, boolean needsReplacement) {
        }

        public record EndoAlert(UUID kitItemId, String kitCode, String kitName, UUID fileId, String modelName, int useCount, int maxUses) {
        }
    }
}
