package com.orthoflow.scheduling.application.dto;

import com.orthoflow.scheduling.domain.model.FurnitureKind;
import com.orthoflow.scheduling.domain.model.WaitingRoomFurniture;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the waiting-room plan and who is sitting in it. */
public final class WaitingRoomLayoutDtos {

    private WaitingRoomLayoutDtos() {
    }

    /**
     * A seat's identity as the browser sees it. Derived, never stored: the pair it
     * encodes is what the database actually keys on.
     */
    public static String seatId(UUID furnitureId, int seatIndex) {
        return furnitureId + "#" + seatIndex;
    }

    // ── Layout ──

    /**
     * One module in a layout being saved. A null id is a module the designer has just
     * drawn; an id that is already in the room updates that module, and any stored
     * module missing from the payload is deleted.
     */
    public record FurnitureRequest(
            UUID id,
            @NotNull FurnitureKind kind,
            @Min(WaitingRoomFurniture.MIN_SEATS) @Max(WaitingRoomFurniture.MAX_SEATS) int seatCount,
            @Min(0) @Max(10_000) int x,
            @Min(0) @Max(10_000) int y,
            @Min(0) @Max(270) int rotation,
            @Min(0) int displayOrder) {
    }

    /** The whole plan for one room, saved in a single transaction. */
    public record LayoutSaveRequest(
            @NotNull UUID waitingRoomId,
            @Valid @NotNull @Size(max = 200) List<FurnitureRequest> furniture) {
    }

    public record FurnitureResponse(UUID id, FurnitureKind kind, int seatCount, int x, int y, int rotation,
                                    int displayOrder, List<String> seatIds) {
        public static FurnitureResponse from(WaitingRoomFurniture f) {
            List<String> seats = java.util.stream.IntStream.range(0, f.getSeatCount())
                    .mapToObj(i -> seatId(f.getId(), i))
                    .toList();
            return new FurnitureResponse(f.getId(), f.getKind(), f.getSeatCount(), f.getPosX(), f.getPosY(),
                    f.getRotation(), f.getDisplayOrder(), seats);
        }
    }

    /**
     * The plan plus its live occupancy. {@code totalSeats} is summed from the modules, so
     * it is always the seats that actually exist — never a stored number that drifted.
     */
    public record LayoutResponse(UUID waitingRoomId, List<FurnitureResponse> furniture, int totalSeats,
                                 List<SeatOccupant> occupants) {
    }

    // ── Occupancy ──

    /** Who is in a seat, and everything the tooltip over that seat shows. */
    public record SeatOccupant(String seatId, UUID furnitureId, int seatIndex, UUID appointmentId, UUID patientId,
                               String patientName, String gender, OffsetDateTime scheduledFor,
                               OffsetDateTime arrivedAt, long waitMinutes, String typeName, String typeColor,
                               OffsetDateTime assignedAt) {
    }

    public record AssignSeatRequest(
            @NotNull UUID appointmentId,
            @NotNull UUID furnitureId,
            @Min(0) @Max(WaitingRoomFurniture.MAX_SEATS - 1) int seatIndex) {
    }

    /**
     * Raised when a layout edit would strand somebody: the module is being deleted, or
     * shrunk past a seat that is taken. Carries the seats at issue so the screen can say
     * who needs moving rather than just refusing.
     */
    public record BlockedSeat(String seatId, UUID appointmentId, String patientName) {
    }
}
