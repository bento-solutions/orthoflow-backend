package com.orthoflow.sterilization.infrastructure;

import com.orthoflow.sterilization.domain.model.EndoFileModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndoFileModelJpaRepository extends JpaRepository<EndoFileModel, UUID> {

    Optional<EndoFileModel> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<EndoFileModel> findByPracticeIdOrderByNameAsc(UUID practiceId);

    boolean existsByPracticeIdAndNameIgnoreCase(UUID practiceId, String name);
}
