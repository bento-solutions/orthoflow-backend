package com.orthoflow.patient.infrastructure.adapter.persistence;

import com.orthoflow.patient.domain.model.ReferralSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralSourceJpaRepository extends JpaRepository<ReferralSource, UUID> {

    List<ReferralSource> findByPracticeIdOrderByDisplayOrderAscNameAsc(UUID practiceId);

    List<ReferralSource> findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(UUID practiceId);

    Optional<ReferralSource> findByIdAndPracticeId(UUID id, UUID practiceId);

    boolean existsByPracticeIdAndNameIgnoreCase(UUID practiceId, String name);
}
