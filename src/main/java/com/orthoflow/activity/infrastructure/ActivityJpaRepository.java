package com.orthoflow.activity.infrastructure;

import com.orthoflow.activity.domain.model.ActivityEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ActivityJpaRepository extends JpaRepository<ActivityEvent, UUID> {

    List<ActivityEvent> findByPracticeIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
            UUID practiceId, String entityType, UUID entityId, Pageable pageable);
}
