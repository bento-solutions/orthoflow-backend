package com.orthoflow.scheduling.application.service;

import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.scheduling.application.dto.WaitingRoomLayoutDtos.*;
import static com.orthoflow.scheduling.application.dto.WaitingRoomLayoutDtos.seatId;
import com.orthoflow.scheduling.domain.model.*;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentTypeJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.WaitingRoomFurnitureJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.WaitingRoomJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.WaitingRoomSeatAssignmentJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The waiting room as a plan, and who is sitting on it.
 *
 * <p>Two jobs that stay apart on purpose. Drawing the room — adding a bench, dragging a
 * sofa, rotating it — is an edit to the furniture, and never moves a patient. Seating
 * somebody is an edit to the assignments, and never moves the furniture. The only place
 * the two meet is a layout change that would strand an occupant, which is refused with
 * the seats named so the screen can say who is in the way.
 *
 * <p>Nothing here touches appointment status. A seat is where a patient waits, not a
 * step in their visit: assigning one does not make them "arrived", and the status
 * changes that do matter release the seat through {@link #releaseFor}.
 */
@Service
@RequiredArgsConstructor
public class WaitingRoomLayoutService {

    private final WaitingRoomFurnitureJpaRepository furniture;
    private final WaitingRoomSeatAssignmentJpaRepository assignments;
    private final WaitingRoomJpaRepository rooms;
    private final AppointmentJpaRepository appointments;
    private final AppointmentTypeJpaRepository types;
    private final PatientLookup patientLookup;
    private final LiveEventPublisher liveEvents;

    // ── Reading ──

    @Transactional(readOnly = true)
    public LayoutResponse layout(UUID practiceId, UUID waitingRoomId) {
        UUID roomId = resolveRoom(practiceId, waitingRoomId);
        List<WaitingRoomFurniture> modules = furniture.findByPracticeIdAndWaitingRoomIdOrderByDisplayOrderAsc(practiceId, roomId);
        int totalSeats = modules.stream().mapToInt(WaitingRoomFurniture::getSeatCount).sum();
        return new LayoutResponse(roomId, modules.stream().map(FurnitureResponse::from).toList(), totalSeats,
                occupants(practiceId, modules));
    }

    /** The live occupants of the given modules, with everything a seat tooltip shows. */
    @Transactional(readOnly = true)
    public List<SeatOccupant> occupants(UUID practiceId, List<WaitingRoomFurniture> modules) {
        if (modules.isEmpty()) {
            return List.of();
        }
        Set<UUID> moduleIds = modules.stream().map(WaitingRoomFurniture::getId).collect(Collectors.toSet());
        List<WaitingRoomSeatAssignment> live = assignments
                .findByPracticeIdAndFurnitureIdInAndReleasedAtIsNull(practiceId, moduleIds);
        if (live.isEmpty()) {
            return List.of();
        }

        Map<UUID, Appointment> byAppointment = appointments
                .findAllById(live.stream().map(WaitingRoomSeatAssignment::getAppointmentId).toList())
                .stream().collect(Collectors.toMap(Appointment::getId, a -> a));
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(
                byAppointment.values().stream().map(Appointment::getPatientId).distinct().toList());
        Map<UUID, AppointmentType> typeById = types
                .findAllById(byAppointment.values().stream().map(Appointment::getAppointmentTypeId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppointmentType::getId, t -> t));

        OffsetDateTime now = OffsetDateTime.now();
        List<SeatOccupant> out = new ArrayList<>(live.size());
        for (WaitingRoomSeatAssignment s : live) {
            Appointment a = byAppointment.get(s.getAppointmentId());
            if (a == null) {
                continue;
            }
            PatientSummary p = patients.get(a.getPatientId());
            AppointmentType t = typeById.get(a.getAppointmentTypeId());
            out.add(new SeatOccupant(
                    seatId(s.getFurnitureId(), s.getSeatIndex()), s.getFurnitureId(), s.getSeatIndex(),
                    a.getId(), a.getPatientId(), p == null ? null : p.fullName(), p == null ? null : p.gender(),
                    a.getDateTime(), a.getArrivedAt(), minutes(a.getArrivedAt(), now), a.getType(),
                    t == null ? null : t.getColor(), s.getAssignedAt()));
        }
        return out;
    }

    /** Seats that exist across the whole practice, and how many of them are taken. */
    @Transactional(readOnly = true)
    public SeatCount seatCount(UUID practiceId) {
        int total = furniture.findByPracticeId(practiceId).stream().mapToInt(WaitingRoomFurniture::getSeatCount).sum();
        int occupied = assignments.findByPracticeIdAndReleasedAtIsNull(practiceId).size();
        return new SeatCount(total, Math.min(occupied, total));
    }

    /** Total and occupied seats, for the front-desk KPI row. */
    public record SeatCount(int total, int occupied) {
    }

    /**
     * Where each seated patient is sitting, keyed by appointment, so the front-desk board
     * can show a seat beside each waiting patient without a second round trip.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> liveSeatsByAppointment(UUID practiceId) {
        return assignments.findByPracticeIdAndReleasedAtIsNull(practiceId).stream()
                .collect(Collectors.toMap(WaitingRoomSeatAssignment::getAppointmentId,
                        s -> seatId(s.getFurnitureId(), s.getSeatIndex()), (a, b) -> a));
    }

    // ── Drawing the room ──

    /**
     * Replaces the plan for one room in a single transaction: modules in the payload are
     * created or updated, and any stored module left out of it is removed.
     *
     * <p>Refused outright — before anything is written — if that would strand a patient,
     * either by deleting a module they are sitting on or by shrinking one past their
     * seat. The refusal names the seats, so reception can move those people first.
     */
    @Transactional
    public LayoutResponse saveLayout(UUID practiceId, LayoutSaveRequest request) {
        UUID roomId = resolveRoom(practiceId, request.waitingRoomId());
        List<WaitingRoomFurniture> existing = furniture.findByPracticeIdAndWaitingRoomIdOrderByDisplayOrderAsc(practiceId, roomId);
        Map<UUID, WaitingRoomFurniture> byId = existing.stream()
                .collect(Collectors.toMap(WaitingRoomFurniture::getId, f -> f));

        // What the payload says each surviving module's capacity will be.
        Map<UUID, Integer> capacityAfter = new HashMap<>();
        for (FurnitureRequest r : request.furniture()) {
            if (r.id() == null) {
                continue;
            }
            if (!byId.containsKey(r.id())) {
                throw new NotFoundException("That seating is not in this waiting room");
            }
            capacityAfter.put(r.id(), r.seatCount());
        }

        List<BlockedSeat> blocked = blockedBy(practiceId, existing, capacityAfter);
        if (!blocked.isEmpty()) {
            throw new ConflictException(describe(blocked));
        }

        Set<UUID> keep = capacityAfter.keySet();
        for (WaitingRoomFurniture stored : existing) {
            if (!keep.contains(stored.getId())) {
                furniture.delete(stored);
            }
        }

        int order = 0;
        for (FurnitureRequest r : request.furniture()) {
            validateRotation(r.rotation());
            WaitingRoomFurniture target = r.id() == null ? new WaitingRoomFurniture() : byId.get(r.id());
            if (r.id() == null) {
                target.setPracticeId(practiceId);
                target.setWaitingRoomId(roomId);
            }
            target.setKind(r.kind());
            target.setSeatCount(r.seatCount());
            target.setPosX(r.x());
            target.setPosY(r.y());
            target.setRotation(r.rotation());
            target.setDisplayOrder(order++);
            furniture.save(target);
        }

        liveEvents.publish(practiceId, "waiting-room-layout", roomId);
        return layout(practiceId, roomId);
    }

    /**
     * The live assignments that the proposed capacities would break. A module absent from
     * {@code capacityAfter} is being deleted, so every occupant of it is blocked; one that
     * is shrinking blocks only the occupants past its new last seat.
     */
    private List<BlockedSeat> blockedBy(UUID practiceId, List<WaitingRoomFurniture> existing, Map<UUID, Integer> capacityAfter) {
        if (existing.isEmpty()) {
            return List.of();
        }
        List<WaitingRoomSeatAssignment> live = assignments.findByPracticeIdAndFurnitureIdInAndReleasedAtIsNull(
                practiceId, existing.stream().map(WaitingRoomFurniture::getId).toList());
        List<WaitingRoomSeatAssignment> doomed = live.stream().filter(s -> {
            Integer after = capacityAfter.get(s.getFurnitureId());
            return after == null || s.getSeatIndex() >= after;
        }).toList();
        if (doomed.isEmpty()) {
            return List.of();
        }
        Map<UUID, Appointment> byAppointment = appointments
                .findAllById(doomed.stream().map(WaitingRoomSeatAssignment::getAppointmentId).toList())
                .stream().collect(Collectors.toMap(Appointment::getId, a -> a));
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(
                byAppointment.values().stream().map(Appointment::getPatientId).distinct().toList());
        return doomed.stream().map(s -> {
            Appointment a = byAppointment.get(s.getAppointmentId());
            PatientSummary p = a == null ? null : patients.get(a.getPatientId());
            return new BlockedSeat(seatId(s.getFurnitureId(), s.getSeatIndex()), s.getAppointmentId(),
                    p == null ? null : p.fullName());
        }).toList();
    }

    private static String describe(List<BlockedSeat> blocked) {
        String who = blocked.stream().map(b -> b.patientName() == null ? "a patient" : b.patientName())
                .distinct().collect(Collectors.joining(", "));
        return "Move " + who + " to another seat before changing that seating";
    }

    // ── Seating people ──

    /**
     * Sits a patient down. The patient must be waiting — somebody already in a chair or
     * not yet arrived has no business in the waiting room — and moving between seats is
     * the same call, which releases the old seat and takes the new one.
     */
    @Transactional
    public LayoutResponse assign(UUID practiceId, AssignSeatRequest request) {
        WaitingRoomFurniture module = furniture.findByIdAndPracticeId(request.furnitureId(), practiceId)
                .orElseThrow(() -> new NotFoundException("That seating is not in this waiting room"));
        if (request.seatIndex() >= module.getSeatCount()) {
            throw new ValidationException("That seat does not exist on this seating");
        }

        Appointment appointment = appointments.findById(request.appointmentId())
                .filter(a -> practiceId.equals(a.getPracticeId()))
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        if (appointment.getStatus() != AppointmentStatus.ARRIVED) {
            throw new ConflictException("Only a patient who is waiting can be given a seat (they are "
                    + appointment.getStatus() + ")");
        }

        assignments.findByPracticeIdAndFurnitureIdAndSeatIndexAndReleasedAtIsNull(practiceId, module.getId(), request.seatIndex())
                .ifPresent(taken -> {
                    if (!taken.getAppointmentId().equals(request.appointmentId())) {
                        throw new ConflictException("That seat is taken");
                    }
                });

        OffsetDateTime now = OffsetDateTime.now();
        // Moving seats: let go of the old one first, in the same transaction, so the
        // one-live-row-per-appointment index never sees two.
        assignments.findByPracticeIdAndAppointmentIdAndReleasedAtIsNull(practiceId, request.appointmentId())
                .ifPresent(previous -> {
                    if (previous.getFurnitureId().equals(module.getId()) && previous.getSeatIndex() == request.seatIndex()) {
                        return;
                    }
                    previous.release(now);
                    assignments.saveAndFlush(previous);
                });

        if (assignments.findByPracticeIdAndAppointmentIdAndReleasedAtIsNull(practiceId, request.appointmentId()).isEmpty()) {
            assignments.save(WaitingRoomSeatAssignment.builder()
                    .practiceId(practiceId)
                    .furnitureId(module.getId())
                    .seatIndex(request.seatIndex())
                    .appointmentId(request.appointmentId())
                    .assignedAt(now)
                    .build());
        }

        liveEvents.publish(practiceId, "waiting-room-seats", module.getWaitingRoomId());
        return layout(practiceId, module.getWaitingRoomId());
    }

    /** Stands a patient up, leaving them checked in but unseated. */
    @Transactional
    public LayoutResponse release(UUID practiceId, UUID appointmentId) {
        WaitingRoomSeatAssignment live = assignments
                .findByPracticeIdAndAppointmentIdAndReleasedAtIsNull(practiceId, appointmentId)
                .orElseThrow(() -> new NotFoundException("That patient is not sitting down"));
        UUID roomId = furniture.findByIdAndPracticeId(live.getFurnitureId(), practiceId)
                .map(WaitingRoomFurniture::getWaitingRoomId).orElse(null);
        live.release(OffsetDateTime.now());
        assignments.save(live);
        liveEvents.publish(practiceId, "waiting-room-seats", roomId);
        return layout(practiceId, roomId);
    }

    /**
     * Frees whatever seat an appointment holds, if any. Called by the front desk when the
     * visit moves on — into a chair, finished, or arrival undone — so a seat is never left
     * holding somebody who has gone. Silent when there is nothing to free.
     */
    @Transactional
    public void releaseFor(UUID practiceId, UUID appointmentId) {
        assignments.findByPracticeIdAndAppointmentIdAndReleasedAtIsNull(practiceId, appointmentId)
                .ifPresent(live -> {
                    live.release(OffsetDateTime.now());
                    assignments.save(live);
                    liveEvents.publish(practiceId, "waiting-room-seats", null);
                });
    }

    // ── Helpers ──

    private UUID resolveRoom(UUID practiceId, UUID waitingRoomId) {
        if (waitingRoomId != null) {
            return rooms.findByIdAndPracticeId(waitingRoomId, practiceId)
                    .map(WaitingRoom::getId)
                    .orElseThrow(() -> new NotFoundException("Waiting room not found"));
        }
        return rooms.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(practiceId).stream()
                .findFirst().map(WaitingRoom::getId)
                .orElseThrow(() -> new NotFoundException("This practice has no waiting room"));
    }

    private static void validateRotation(int rotation) {
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new ValidationException("Seating turns in quarter turns: 0, 90, 180 or 270");
        }
    }

    private static long minutes(OffsetDateTime from, OffsetDateTime to) {
        return from == null || to == null ? 0 : Math.max(0, Duration.between(from, to).toMinutes());
    }
}
