package com.orthoflow.sterilization.domain.model;

import org.hibernate.annotations.TenantId;
import com.orthoflow.common.exception.ConflictException;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One run of an autoclave: which machine, which cycle number, the program, who ran
 * it and what the control said. Items are linked through
 * {@code sterilization_cycle_items}. A control starts PENDING and is decided once:
 * PASSED releases the load, FAILED recalls it. A PASSED cycle may later turn FAILED
 * (a biological indicator reads days after the chemical one); a FAILED one may not
 * turn back, because the remedy is a new cycle, not a second opinion.
 */
@Entity
@Table(name = "sterilization_cycles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SterilizationCycle {

    public enum ControlType { CHEMICAL, BIOLOGICAL, PHYSICAL }

    public enum ControlResult { PENDING, PASSED, FAILED }

    @Id
    private UUID id;

    @Version
    private Long version;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "autoclave_id", nullable = false)
    private UUID autoclaveId;

    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    @Column(nullable = false)
    private String program;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "operator_id")
    private UUID operatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_type", nullable = false)
    @Builder.Default
    private ControlType controlType = ControlType.CHEMICAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_result", nullable = false)
    @Builder.Default
    private ControlResult controlResult = ControlResult.PENDING;

    @Column(name = "controlled_at")
    private OffsetDateTime controlledAt;

    @Column(name = "controlled_by")
    private UUID controlledBy;

    @Column(name = "control_note", columnDefinition = "TEXT")
    private String controlNote;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    /** Records the control. Returns true when this changed the result. */
    public boolean decide(ControlResult result, UUID by, String note, OffsetDateTime at) {
        if (result == ControlResult.PENDING) {
            throw new ConflictException("A control is recorded as PASSED or FAILED");
        }
        if (controlResult == result) {
            return false;
        }
        if (controlResult == ControlResult.FAILED) {
            throw new ConflictException("A failed cycle stays failed; run a new cycle for the load");
        }
        controlResult = result;
        controlledBy = by;
        controlledAt = at;
        controlNote = note;
        if (finishedAt == null) {
            finishedAt = at;
        }
        return true;
    }
}
