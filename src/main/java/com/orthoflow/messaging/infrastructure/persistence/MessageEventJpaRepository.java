package com.orthoflow.messaging.infrastructure.persistence;

import com.orthoflow.messaging.domain.model.MessageEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MessageEventJpaRepository extends JpaRepository<MessageEvent, UUID> {

    @Query("""
            SELECT e FROM MessageEvent e
            WHERE e.practiceId = :practiceId AND e.direction = 'IN' AND (:unhandledOnly = false OR e.handledAt IS NULL)
              AND (:landingPageOnly = false OR e.fromLandingPage = true)
            ORDER BY e.occurredAt DESC
            """)
    List<MessageEvent> inbox(@Param("practiceId") UUID practiceId, @Param("unhandledOnly") boolean unhandledOnly,
                             @Param("landingPageOnly") boolean landingPageOnly, Pageable pageable);

    @Query("SELECT COUNT(e) FROM MessageEvent e WHERE e.practiceId = :practiceId AND e.direction = 'IN' AND e.handledAt IS NULL")
    long unhandledCount(@Param("practiceId") UUID practiceId);

    boolean existsByDirectionAndEventTypeAndExternalId(String direction, String eventType, String externalId);
}
