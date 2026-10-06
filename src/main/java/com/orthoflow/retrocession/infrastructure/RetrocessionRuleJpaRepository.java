package com.orthoflow.retrocession.infrastructure;

import com.orthoflow.retrocession.domain.model.RetrocessionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetrocessionRuleJpaRepository extends JpaRepository<RetrocessionRule, UUID> {

    Optional<RetrocessionRule> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<RetrocessionRule> findByPracticeIdOrderByPractitionerIdAscEffectiveFromDesc(UUID practiceId);

    List<RetrocessionRule> findByPracticeIdAndPractitionerIdOrderByEffectiveFromDesc(UUID practiceId, UUID practitionerId);

    /** Rules whose validity touches [from, to]. */
    @Query("""
            SELECT r FROM RetrocessionRule r
            WHERE r.practiceId = :practiceId AND r.effectiveFrom <= :to
              AND (r.effectiveTo IS NULL OR r.effectiveTo >= :from)
              AND (:practitionerId IS NULL OR r.practitionerId = :practitionerId)
            ORDER BY r.practitionerId, r.effectiveFrom
            """)
    List<RetrocessionRule> findOverlapping(@Param("practiceId") UUID practiceId, @Param("from") LocalDate from,
                                           @Param("to") LocalDate to, @Param("practitionerId") UUID practitionerId);
}
