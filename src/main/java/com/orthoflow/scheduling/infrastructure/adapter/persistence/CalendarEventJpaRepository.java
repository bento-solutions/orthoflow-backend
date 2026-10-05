package com.orthoflow.scheduling.infrastructure.adapter.persistence;

import com.orthoflow.scheduling.domain.model.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarEventJpaRepository extends JpaRepository<CalendarEvent, UUID> {

    @Query("""
            SELECT e FROM CalendarEvent e
            WHERE e.practiceId = :practiceId AND e.startsAt < :to AND e.endsAt > :from
            ORDER BY e.startsAt
            """)
    List<CalendarEvent> overlapping(@Param("practiceId") UUID practiceId, @Param("from") OffsetDateTime from,
                                    @Param("to") OffsetDateTime to);

    /** Events that close this slot for this booking: clinic-wide ones, or ones on its chair or its practitioner. */
    @Query("""
            SELECT e FROM CalendarEvent e
            WHERE e.practiceId = :practiceId AND e.startsAt < :to AND e.endsAt > :from
              AND ((e.chairId IS NULL AND e.practitionerId IS NULL)
                   OR (:chairId IS NOT NULL AND e.chairId = :chairId)
                   OR (:practitionerId IS NOT NULL AND e.practitionerId = :practitionerId))
            """)
    List<CalendarEvent> blocking(@Param("practiceId") UUID practiceId, @Param("from") OffsetDateTime from,
                                 @Param("to") OffsetDateTime to, @Param("chairId") UUID chairId,
                                 @Param("practitionerId") UUID practitionerId);

    Optional<CalendarEvent> findByIdAndPracticeId(UUID id, UUID practiceId);
}
