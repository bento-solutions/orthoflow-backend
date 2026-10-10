package com.orthoflow.imaging.infrastructure;

import com.orthoflow.imaging.domain.model.ClinicalPhotoSeries;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClinicalPhotoSeriesJpaRepository extends JpaRepository<ClinicalPhotoSeries, UUID> {

    Optional<ClinicalPhotoSeries> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<ClinicalPhotoSeries> findByPracticeIdAndPatientIdOrderByTakenOnDescCreatedAtDesc(UUID practiceId, UUID patientId);
}
