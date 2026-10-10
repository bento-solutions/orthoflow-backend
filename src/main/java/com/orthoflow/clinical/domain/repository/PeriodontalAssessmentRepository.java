package com.orthoflow.clinical.domain.repository;

import com.orthoflow.clinical.domain.model.PeriodontalAssessment;

import java.util.List;
import java.util.UUID;

public interface PeriodontalAssessmentRepository {
    PeriodontalAssessment save(PeriodontalAssessment assessment);

    /** Every assessment of the patient, newest first. */
    List<PeriodontalAssessment> findByPatient(UUID patientId);
}
