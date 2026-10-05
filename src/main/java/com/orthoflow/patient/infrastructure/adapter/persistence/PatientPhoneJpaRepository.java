package com.orthoflow.patient.infrastructure.adapter.persistence;

import com.orthoflow.patient.domain.model.PatientPhone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PatientPhoneJpaRepository extends JpaRepository<PatientPhone, UUID> {

    List<PatientPhone> findByPatientIdOrderByCreatedAtAsc(UUID patientId);

    void deleteByPatientId(UUID patientId);
}
