package com.orthoflow.clinical.infrastructure.adapter.persistence;

import com.orthoflow.clinical.domain.model.PeriodontalAssessment;
import com.orthoflow.clinical.domain.repository.PeriodontalAssessmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PeriodontalAssessmentRepositoryAdapter implements PeriodontalAssessmentRepository {

    private final PeriodontalAssessmentJpaRepository jpaRepository;

    @Override
    public PeriodontalAssessment save(PeriodontalAssessment assessment) {
        return jpaRepository.save(assessment);
    }

    @Override
    public List<PeriodontalAssessment> findByPatient(UUID patientId) {
        return jpaRepository.findByPatientIdOrderByAssessedOnDescCreatedAtDesc(patientId);
    }
}
