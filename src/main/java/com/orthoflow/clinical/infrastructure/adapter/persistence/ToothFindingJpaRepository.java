package com.orthoflow.clinical.infrastructure.adapter.persistence;

import com.orthoflow.clinical.domain.model.FindingStatus;
import com.orthoflow.clinical.domain.model.ToothFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToothFindingJpaRepository extends JpaRepository<ToothFinding, UUID> {
    List<ToothFinding> findByChartIdAndStatusOrderByCreatedAtAsc(UUID chartId, FindingStatus status);
    List<ToothFinding> findByChartIdAndStatusNotOrderByCreatedAtAsc(UUID chartId, FindingStatus status);
    List<ToothFinding> findByChartIdAndFdiAndStatusOrderByCreatedAtAsc(UUID chartId, String fdi, FindingStatus status);
    Optional<ToothFinding> findByChartIdAndFdiAndFindingCodeAndStatus(
            UUID chartId, String fdi, String findingCode, FindingStatus status);
    Optional<ToothFinding> findByChartIdAndFdiAndFindingCodeAndSurfaceAndStatus(
            UUID chartId, String fdi, String findingCode, String surface, FindingStatus status);
    Optional<ToothFinding> findByChartIdAndFdiAndFindingCodeAndSurfaceIsNullAndStatus(
            UUID chartId, String fdi, String findingCode, FindingStatus status);
    List<ToothFinding> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
