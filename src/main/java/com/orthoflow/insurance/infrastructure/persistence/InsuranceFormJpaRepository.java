package com.orthoflow.insurance.infrastructure.persistence;

import com.orthoflow.insurance.domain.model.InsuranceForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsuranceFormJpaRepository extends JpaRepository<InsuranceForm, UUID> {

    Optional<InsuranceForm> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<InsuranceForm> findByConsultationIdOrderByCreatedAtAsc(UUID consultationId);

    @Query("""
            SELECT f FROM InsuranceForm f
            WHERE f.practiceId = :practiceId
              AND (:patientId IS NULL OR f.patientId = :patientId)
              AND (:status IS NULL OR f.status = :status)
            ORDER BY f.createdAt DESC
            """)
    List<InsuranceForm> search(@Param("practiceId") UUID practiceId, @Param("patientId") UUID patientId,
                               @Param("status") InsuranceForm.Status status, org.springframework.data.domain.Pageable page);
}
