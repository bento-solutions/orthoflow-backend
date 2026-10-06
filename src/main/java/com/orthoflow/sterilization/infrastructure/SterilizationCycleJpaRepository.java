package com.orthoflow.sterilization.infrastructure;

import com.orthoflow.sterilization.domain.model.SterilizationCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SterilizationCycleJpaRepository extends JpaRepository<SterilizationCycle, UUID> {

    Optional<SterilizationCycle> findByIdAndPracticeId(UUID id, UUID practiceId);

    @Query("SELECT COALESCE(MAX(c.cycleNumber), 0) FROM SterilizationCycle c WHERE c.autoclaveId = :autoclaveId")
    int maxNumber(@Param("autoclaveId") UUID autoclaveId);
}
