package com.orthoflow.prescription.infrastructure;

import com.orthoflow.prescription.domain.model.PrescriptionTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrescriptionTemplateJpaRepository extends JpaRepository<PrescriptionTemplate, UUID> {

    Optional<PrescriptionTemplate> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<PrescriptionTemplate> findByPracticeIdOrderByCategoryAscNameAsc(UUID practiceId);

    boolean existsByPracticeIdAndNameIgnoreCase(UUID practiceId, String name);

    boolean existsByPracticeIdAndLibraryCode(UUID practiceId, String libraryCode);
}
