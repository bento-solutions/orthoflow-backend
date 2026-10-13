package com.orthoflow.scheduling.infrastructure.adapter.persistence;

import com.orthoflow.scheduling.domain.model.WaitingRoomSeatAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitingRoomSeatAssignmentJpaRepository extends JpaRepository<WaitingRoomSeatAssignment, UUID> {

    /** Everyone sitting down right now, across the whole practice. */
    List<WaitingRoomSeatAssignment> findByPracticeIdAndReleasedAtIsNull(UUID practiceId);

    /** The live rows for a set of modules — used to check what a layout edit would disturb. */
    List<WaitingRoomSeatAssignment> findByPracticeIdAndFurnitureIdInAndReleasedAtIsNull(UUID practiceId, Collection<UUID> furnitureIds);

    Optional<WaitingRoomSeatAssignment> findByPracticeIdAndAppointmentIdAndReleasedAtIsNull(UUID practiceId, UUID appointmentId);

    Optional<WaitingRoomSeatAssignment> findByPracticeIdAndFurnitureIdAndSeatIndexAndReleasedAtIsNull(UUID practiceId, UUID furnitureId, int seatIndex);
}
