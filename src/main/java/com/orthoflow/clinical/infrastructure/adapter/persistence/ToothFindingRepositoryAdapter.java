package com.orthoflow.clinical.infrastructure.adapter.persistence;

import com.orthoflow.clinical.domain.model.FindingStatus;
import com.orthoflow.clinical.domain.model.ToothFinding;
import com.orthoflow.clinical.domain.repository.ToothFindingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ToothFindingRepositoryAdapter implements ToothFindingRepository {

    private final ToothFindingJpaRepository jpaRepository;

    @Override
    public ToothFinding save(ToothFinding finding) {
        return jpaRepository.save(finding);
    }

    @Override
    public Optional<ToothFinding> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<ToothFinding> findActiveByChart(UUID chartId) {
        return jpaRepository.findByChartIdAndStatusOrderByCreatedAtAsc(chartId, FindingStatus.ACTIVE);
    }

    @Override
    public List<ToothFinding> findHistoryByChart(UUID chartId) {
        return jpaRepository.findByChartIdAndStatusNotOrderByCreatedAtAsc(chartId, FindingStatus.RETRACTED);
    }

    @Override
    public List<ToothFinding> findActiveByChartAndFdi(UUID chartId, String fdi) {
        return jpaRepository.findByChartIdAndFdiAndStatusOrderByCreatedAtAsc(chartId, fdi, FindingStatus.ACTIVE);
    }

    @Override
    public Optional<ToothFinding> findActiveByChartFdiAndCode(UUID chartId, String fdi, String findingCode) {
        return jpaRepository.findByChartIdAndFdiAndFindingCodeAndStatus(
                chartId, fdi, findingCode, FindingStatus.ACTIVE);
    }

    @Override
    public Optional<ToothFinding> findActiveByChartFdiCodeAndSurface(
            UUID chartId, String fdi, String findingCode, String surface) {
        return surface == null
                ? jpaRepository.findByChartIdAndFdiAndFindingCodeAndSurfaceIsNullAndStatus(
                        chartId, fdi, findingCode, FindingStatus.ACTIVE)
                : jpaRepository.findByChartIdAndFdiAndFindingCodeAndSurfaceAndStatus(
                        chartId, fdi, findingCode, surface, FindingStatus.ACTIVE);
    }

    @Override
    public List<ToothFinding> findBySession(UUID sessionId) {
        return jpaRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }
}
