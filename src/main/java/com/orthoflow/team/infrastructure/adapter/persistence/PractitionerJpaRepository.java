package com.orthoflow.team.infrastructure.adapter.persistence;

import com.orthoflow.team.domain.model.Practitioner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PractitionerJpaRepository extends JpaRepository<Practitioner, UUID> {

    List<Practitioner> findByPracticeIdOrderByDisplayOrderAscDisplayNameAsc(UUID practiceId);

    List<Practitioner> findByPracticeIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAsc(UUID practiceId);

    Optional<Practitioner> findByUserId(UUID userId);

    Optional<Practitioner> findByIdAndPracticeId(UUID id, UUID practiceId);

    boolean existsByUserId(UUID userId);
}
