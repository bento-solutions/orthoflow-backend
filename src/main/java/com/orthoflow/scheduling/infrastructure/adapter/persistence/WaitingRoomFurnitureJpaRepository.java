package com.orthoflow.scheduling.infrastructure.adapter.persistence;

import com.orthoflow.scheduling.domain.model.WaitingRoomFurniture;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitingRoomFurnitureJpaRepository extends JpaRepository<WaitingRoomFurniture, UUID> {

    List<WaitingRoomFurniture> findByPracticeIdAndWaitingRoomIdOrderByDisplayOrderAsc(UUID practiceId, UUID waitingRoomId);

    List<WaitingRoomFurniture> findByPracticeId(UUID practiceId);

    Optional<WaitingRoomFurniture> findByIdAndPracticeId(UUID id, UUID practiceId);
}
