package com.orthoflow.scheduling.infrastructure.adapter.persistence;

import com.orthoflow.scheduling.domain.model.WaitingRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitingRoomJpaRepository extends JpaRepository<WaitingRoom, UUID> {

    List<WaitingRoom> findByPracticeIdOrderByDisplayOrderAscNameAsc(UUID practiceId);

    List<WaitingRoom> findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(UUID practiceId);

    Optional<WaitingRoom> findByIdAndPracticeId(UUID id, UUID practiceId);
}
