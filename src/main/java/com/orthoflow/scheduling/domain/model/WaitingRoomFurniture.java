package com.orthoflow.scheduling.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One seating module drawn on the waiting-room plan: a bench or a sofa, somewhere in the
 * room, holding one to five seats.
 *
 * <p>This is the layout only. Who is sitting in it is a {@link WaitingRoomSeatAssignment},
 * so a module can be moved, rotated or reordered without touching anybody's seat.
 */
@Entity
@Table(name = "waiting_room_furniture")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaitingRoomFurniture {

    /** The smallest and largest module the designer can draw. */
    public static final int MIN_SEATS = 1;
    public static final int MAX_SEATS = 5;

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(name = "waiting_room_id", nullable = false)
    private UUID waitingRoomId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FurnitureKind kind;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    /** Top-left corner in room units — the canvas grid, not pixels. */
    @Column(name = "pos_x", nullable = false)
    private int posX;

    @Column(name = "pos_y", nullable = false)
    private int posY;

    /** 0, 90, 180 or 270 degrees clockwise. */
    @Column(nullable = false)
    private int rotation;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
