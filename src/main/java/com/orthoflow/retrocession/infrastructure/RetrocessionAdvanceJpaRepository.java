package com.orthoflow.retrocession.infrastructure;

import com.orthoflow.retrocession.domain.model.RetrocessionAdvance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetrocessionAdvanceJpaRepository extends JpaRepository<RetrocessionAdvance, UUID> {

    Optional<RetrocessionAdvance> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<RetrocessionAdvance> findByPracticeIdOrderByAdvanceDateDescCreatedAtDesc(UUID practiceId);

    List<RetrocessionAdvance> findByPracticeIdAndPractitionerIdOrderByAdvanceDateDescCreatedAtDesc(UUID practiceId, UUID practitionerId);
}
