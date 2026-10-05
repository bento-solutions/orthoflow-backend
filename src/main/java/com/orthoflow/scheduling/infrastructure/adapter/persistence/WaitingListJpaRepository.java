package com.orthoflow.scheduling.infrastructure.adapter.persistence;

import com.orthoflow.scheduling.domain.model.WaitingListEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitingListJpaRepository extends JpaRepository<WaitingListEntry, UUID> {

    List<WaitingListEntry> findByPracticeIdAndStatusOrderByCreatedAtAsc(UUID practiceId, WaitingListEntry.Status status);

    Optional<WaitingListEntry> findByIdAndPracticeId(UUID id, UUID practiceId);
}
