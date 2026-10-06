package com.orthoflow.scheduling.application.dto;

import com.orthoflow.scheduling.domain.model.*;
import jakarta.validation.constraints.*;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the agenda configuration, waiting list and front-desk screens. */
public final class AgendaDtos {

    private AgendaDtos() {
    }

    private static final String HEX = "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$";

    // ── Appointment types ──
    public record TypeRequest(
            @Size(max = 40) @Pattern(regexp = "^[A-Z0-9_]*$", message = "code uses capitals, digits and underscores") String code,
            @NotBlank @Size(max = 120) String nameFr, @NotBlank @Size(max = 120) String nameEn,
            @NotBlank @Size(max = 120) String nameAr, @Pattern(regexp = HEX) String color,
            @Min(5) @Max(480) Integer defaultDurationMinutes, Boolean bookableOnline,
            @Pattern(regexp = "ORTHODONTICS|GENERAL|ANY") String specialtyGroup, Boolean active, Integer displayOrder) {
    }

    public record TypeResponse(UUID id, String code, String nameFr, String nameEn, String nameAr, String color,
                               int defaultDurationMinutes, boolean bookableOnline, String specialtyGroup,
                               boolean active, int displayOrder) {
        public static TypeResponse from(AppointmentType t) {
            return new TypeResponse(t.getId(), t.getCode(), t.getNameFr(), t.getNameEn(), t.getNameAr(), t.getColor(),
                    t.getDefaultDurationMinutes(), t.isBookableOnline(), t.getSpecialtyGroup(), t.isActive(), t.getDisplayOrder());
        }
    }

    // ── Rooms and chairs ──
    public record RoomRequest(@NotBlank @Size(max = 100) String name, Boolean active, Integer displayOrder) {
    }

    public record RoomResponse(UUID id, String name, boolean active, int displayOrder) {
        public static RoomResponse from(WaitingRoom r) {
            return new RoomResponse(r.getId(), r.getName(), r.isActive(), r.getDisplayOrder());
        }
    }

    public record ChairRequest(@NotBlank @Size(max = 100) String name, Boolean active, Integer displayOrder) {
    }

    public record ChairDetail(UUID id, String name, boolean active, int displayOrder) {
        public static ChairDetail from(Chair c) {
            return new ChairDetail(c.getId(), c.getName(), c.isActive(), c.getDisplayOrder());
        }
    }

    // ── Absences and events ──
    public record AbsenceRequest(@NotNull UUID practitionerId, @NotNull OffsetDateTime startsAt,
                                 @NotNull OffsetDateTime endsAt, AbsenceReason reason, String notes) {
    }

    public record AbsenceResponse(UUID id, UUID practitionerId, String practitionerName, OffsetDateTime startsAt,
                                  OffsetDateTime endsAt, AbsenceReason reason, String notes) {
    }

    public record EventRequest(@NotBlank @Size(max = 200) String title, @NotNull OffsetDateTime startsAt,
                               @NotNull OffsetDateTime endsAt, UUID chairId, UUID practitionerId,
                               @Pattern(regexp = HEX) String color, String notes) {
    }

    public record EventResponse(UUID id, String title, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID chairId,
                                UUID practitionerId, String color, String notes) {
        public static EventResponse from(CalendarEvent e) {
            return new EventResponse(e.getId(), e.getTitle(), e.getStartsAt(), e.getEndsAt(), e.getChairId(),
                    e.getPractitionerId(), e.getColor(), e.getNotes());
        }
    }

    // ── Waiting list ──
    public record WaitingEntryRequest(@NotNull UUID patientId, UUID appointmentTypeId, UUID practitionerId,
                                      @Min(5) @Max(480) Integer durationMinutes,
                                      @Pattern(regexp = "^([1-7](,[1-7])*)?$", message = "weekdays as 1-7 separated by commas") String preferredWeekdays,
                                      LocalTime preferredFrom, LocalTime preferredTo,
                                      WaitingListEntry.Urgency urgency, String notes) {
    }

    public record WaitingEntryResponse(UUID id, UUID patientId, String patientName, String patientPhone,
                                       UUID appointmentTypeId, String typeName, String typeColor, UUID practitionerId,
                                       String practitionerName, int durationMinutes, String preferredWeekdays,
                                       LocalTime preferredFrom, LocalTime preferredTo, WaitingListEntry.Urgency urgency,
                                       String notes, WaitingListEntry.Status status, OffsetDateTime createdAt) {
    }

    public record ScheduleFromWaiting(@NotNull OffsetDateTime dateTime, UUID chairId, UUID practitionerId,
                                      Boolean ignoreBlocks) {
    }

    // ── Front desk ──
    public record CheckIn(UUID waitingRoomId) {
    }

    public record Seat(@NotNull UUID chairId) {
    }

    /** A patient who walked in without a booking: an existing one, or just enough to register one. */
    public record WalkIn(UUID patientId, @Size(max = 255) String firstName, @Size(max = 255) String lastName,
                         @Size(max = 40) String phone, UUID appointmentTypeId, UUID practitionerId,
                         String notes, UUID waitingRoomId) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "AgendaReorder")

    public record Reorder(@NotEmpty List<UUID> orderedIds) {
    }

    public record WaitingCard(UUID appointmentId, UUID patientId, String patientName, String patientPhone,
                              String typeName, String typeColor, UUID practitionerId, String practitionerName,
                              OffsetDateTime scheduledFor, OffsetDateTime arrivedAt, long waitMinutes,
                              int priority, UUID waitingRoomId, boolean walkIn) {
    }

    public record ChairBoard(UUID chairId, String name, boolean occupied, UUID appointmentId, String patientName,
                             String practitionerName, OffsetDateTime since, Long minutes) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "AgendaKpis")

    public record Kpis(int waiting, int inTreatment, int arrivedToday, Double averageWaitMinutes,
                       Long longestWaitMinutes, int chairsTotal, int chairsOccupied) {
    }

    public record FrontDesk(Kpis kpis, List<WaitingCard> waiting, List<ChairBoard> chairs) {
    }
}
