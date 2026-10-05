package com.orthoflow.scheduling.infrastructure.adapter.persistence;

import com.orthoflow.scheduling.domain.model.PractitionerAbsence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PractitionerAbsenceJpaRepository extends JpaRepository<PractitionerAbsence, UUID> {

    @Query("""
            SELECT a FROM PractitionerAbsence a
            WHERE a.practiceId = :practiceId AND a.startsAt < :to AND a.endsAt > :from
            ORDER BY a.startsAt
            """)
    List<PractitionerAbsence> overlapping(@Param("practiceId") UUID practiceId, @Param("from") OffsetDateTime from,
                                          @Param("to") OffsetDateTime to);

    @Query("""
            SELECT a FROM PractitionerAbsence a
            WHERE a.practitionerId = :practitionerId AND a.startsAt < :to AND a.endsAt > :from
            """)
    List<PractitionerAbsence> overlappingFor(@Param("practitionerId") UUID practitionerId,
                                             @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    Optional<PractitionerAbsence> findByIdAndPracticeId(UUID id, UUID practiceId);
}
