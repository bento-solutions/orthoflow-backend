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
              AND (:urgentOnly = false OR e.urgent = true)
            ORDER BY CASE WHEN e.handledAt IS NULL AND e.urgent = true THEN 0 ELSE 1 END, e.occurredAt DESC
            """)
    List<MessageEvent> inbox(@Param("practiceId") UUID practiceId, @Param("unhandledOnly") boolean unhandledOnly,
                             @Param("landingPageOnly") boolean landingPageOnly, @Param("urgentOnly") boolean urgentOnly,
                             Pageable pageable);

    @Query("SELECT COUNT(e) FROM MessageEvent e WHERE e.practiceId = :practiceId AND e.direction = 'IN' AND e.handledAt IS NULL")
    long unhandledCount(@Param("practiceId") UUID practiceId);

    @Query("SELECT COUNT(e) FROM MessageEvent e WHERE e.practiceId = :practiceId AND e.direction = 'IN' AND e.handledAt IS NULL AND e.urgent = true")
    long unhandledUrgentCount(@Param("practiceId") UUID practiceId);

    boolean existsByDirectionAndEventTypeAndExternalId(String direction, String eventType, String externalId);
}
