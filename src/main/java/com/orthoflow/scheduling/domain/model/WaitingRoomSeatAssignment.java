package com.orthoflow.scheduling.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One patient sitting in one seat, for as long as they sit there.
 *
 * <p>A seat is {@code (furnitureId, seatIndex)} rather than a row of its own, which is
 * what keeps an assignment attached to its seat while the module around it is moved or
 * rotated. {@code releasedAt == null} means the seat is taken right now; the database
 * enforces one live row per seat and one per appointment.
 *
 * <p>Released rows are kept rather than deleted — they are the record of who sat where
 * and for how long.
 */
@Entity
@Table(name = "waiting_room_seat_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaitingRoomSeatAssignment {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "furniture_id", nullable = false)
    private UUID furnitureId;

    /** Zero-based position within the module, left to right before rotation. */
    @Column(name = "seat_index", nullable = false)
    private int seatIndex;

    @Column(name = "appointment_id", nullable = false)
    private UUID appointmentId;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private OffsetDateTime assignedAt;

    /** Null while the patient is still sitting there. */
    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    public boolean isLive() {
        return releasedAt == null;
    }

    public void release(OffsetDateTime at) {
        if (releasedAt == null) {
            releasedAt = at;
        }
    }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (assignedAt == null) assignedAt = OffsetDateTime.now();
    }
}
