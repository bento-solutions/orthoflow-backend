package com.orthoflow.patient.infrastructure.adapter.persistence;

import com.orthoflow.patient.domain.model.Insurer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsurerJpaRepository extends JpaRepository<Insurer, UUID> {

    List<Insurer> findByPracticeIdOrderByNameAsc(UUID practiceId);

    List<Insurer> findByPracticeIdAndActiveTrueOrderByNameAsc(UUID practiceId);

    Optional<Insurer> findByIdAndPracticeId(UUID id, UUID practiceId);

    boolean existsByPracticeIdAndCodeIgnoreCase(UUID practiceId, String code);
}
