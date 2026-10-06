package com.orthoflow.sterilization.domain.model;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A tray, instrument, handpiece or endo kit that goes through sterilization.
 *
 * <p>The state cycle is READY → USED → DIRTY → PROCESSED → READY and every move
 * is a method here that refuses anything out of order, so no caller can mark a
 * dirty tray ready. A new item starts DIRTY: nothing is assumed sterile until a
 * cycle whose control passed has said so.
 */
@Entity
@Table(name = "sterilization_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SterilizationItem {

    public enum Kind { TRAY, INSTRUMENT, HANDPIECE, ENDO_KIT }

    public enum State { READY, USED, DIRTY, PROCESSED }

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "qr_token", nullable = false, updatable = false)
    private String qrToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private State state = State.DIRTY;

    @Column(name = "state_changed_at", nullable = false)
    private OffsetDateTime stateChangedAt;

    @Column(name = "last_cycle_id")
    private UUID lastCycleId;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "last_lubricated_at")
    private OffsetDateTime lastLubricatedAt;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (qrToken == null) qrToken = newToken();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (stateChangedAt == null) stateChangedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** 128 bits from a random UUID, without the dashes: opaque, unguessable, short enough for a small QR. */
    static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ── The cycle ──

    /** READY → USED. Only a sterile item may touch a patient. */
    public void use(OffsetDateTime at) {
        if (state != State.READY) {
            throw new ConflictException(label() + " is " + state + " and cannot be used: only a READY item has been through a passed sterilization cycle");
        }
        move(State.USED, at);
        lastUsedAt = at;
    }

    /** USED → DIRTY: sent for cleaning. */
    public void sendToCleaning(OffsetDateTime at) {
        if (state != State.USED) {
            throw new ConflictException(label() + " is " + state + "; only a used item is sent to cleaning");
        }
        move(State.DIRTY, at);
    }

    /** DIRTY → PROCESSED: loaded into an autoclave cycle. */
    public void process(UUID cycleId, OffsetDateTime at) {
        if (state != State.DIRTY) {
            throw new ConflictException(label() + " is " + state + "; only a cleaned (DIRTY) item can go into a cycle");
        }
        move(State.PROCESSED, at);
        lastCycleId = cycleId;
    }

    /** PROCESSED → READY: the cycle's control passed. */
    public void release(OffsetDateTime at) {
        if (state != State.PROCESSED) {
            throw new ConflictException(label() + " is " + state + "; only a processed item is released");
        }
        move(State.READY, at);
    }

    /** READY or PROCESSED → DIRTY: the cycle that sterilised it failed its control, so it is reprocessed. */
    public void recall(OffsetDateTime at) {
        if (state != State.READY && state != State.PROCESSED) {
            throw new ConflictException(label() + " is " + state + " and cannot be recalled");
        }
        move(State.DIRTY, at);
    }

    /** Handpieces are lubricated during reprocessing, after cleaning and before the autoclave. */
    public void lubricate(OffsetDateTime at) {
        if (kind != Kind.HANDPIECE) {
            throw new ConflictException(label() + " is not a handpiece; only handpieces are lubricated");
        }
        if (state != State.DIRTY) {
            throw new ConflictException(label() + " is " + state + "; a handpiece is lubricated after cleaning, before it goes into a cycle");
        }
        lastLubricatedAt = at;
    }

    /** True when a handpiece has been used since it was last lubricated, or never lubricated at all. */
    public boolean lubricationDue() {
        return kind == Kind.HANDPIECE && (lastLubricatedAt == null || (lastUsedAt != null && lastLubricatedAt.isBefore(lastUsedAt)));
    }

    private void move(State to, OffsetDateTime at) {
        state = to;
        stateChangedAt = at;
    }

    private String label() {
        return code + " (" + name + ")";
    }
}
