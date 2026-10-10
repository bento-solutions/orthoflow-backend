package com.orthoflow.clinical.infrastructure.adapter.persistence;

import com.orthoflow.clinical.domain.model.PeriodontalAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PeriodontalAssessmentJpaRepository extends JpaRepository<PeriodontalAssessment, UUID> {
    List<PeriodontalAssessment> findByPatientIdOrderByAssessedOnDescCreatedAtDesc(UUID patientId);
}
