package com.orthoflow.prescription.infrastructure;

import com.orthoflow.prescription.domain.model.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrescriptionJpaRepository extends JpaRepository<Prescription, UUID> {

    Optional<Prescription> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<Prescription> findTop100ByPracticeIdAndPatientIdOrderByIssuedAtDesc(UUID practiceId, UUID patientId);
}
