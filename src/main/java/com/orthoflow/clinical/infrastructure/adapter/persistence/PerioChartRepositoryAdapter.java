package com.orthoflow.clinical.infrastructure.adapter.persistence;

import com.orthoflow.clinical.domain.model.PerioChart;
import com.orthoflow.clinical.domain.repository.PerioChartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PerioChartRepositoryAdapter implements PerioChartRepository {

    private final PerioChartJpaRepository jpaRepository;

    @Override
    public PerioChart save(PerioChart chart) {
        return jpaRepository.saveAndFlush(chart);
    }

    @Override
    public Optional<PerioChart> findOfPatient(UUID patientId, UUID chartId) {
        return jpaRepository.findByIdAndPatientId(chartId, patientId);
    }

    @Override
    public List<PerioChart> findAlive(UUID patientId) {
        return jpaRepository.findByPatientIdAndDeletedAtIsNullOrderByChartDateDescCreatedAtDesc(patientId);
    }

    @Override
    public List<PerioChart> findDeleted(UUID patientId) {
        return jpaRepository.findByPatientIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(patientId);
    }
}
