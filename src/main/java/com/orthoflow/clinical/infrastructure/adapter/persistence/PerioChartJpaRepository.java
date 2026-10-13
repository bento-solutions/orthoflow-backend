package com.orthoflow.clinical.infrastructure.adapter.persistence;

import com.orthoflow.clinical.domain.model.PerioChart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PerioChartJpaRepository extends JpaRepository<PerioChart, UUID> {

    Optional<PerioChart> findByIdAndPatientId(UUID id, UUID patientId);

    List<PerioChart> findByPatientIdAndDeletedAtIsNullOrderByChartDateDescCreatedAtDesc(UUID patientId);

    List<PerioChart> findByPatientIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(UUID patientId);
}
