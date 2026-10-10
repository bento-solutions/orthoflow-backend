package com.orthoflow.clinical.domain.repository;

import com.orthoflow.clinical.domain.model.ToothFinding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToothFindingRepository {
    ToothFinding save(ToothFinding finding);
    Optional<ToothFinding> findById(UUID id);
    List<ToothFinding> findActiveByChart(UUID chartId);
    List<ToothFinding> findActiveByChartAndFdi(UUID chartId, String fdi);
    /** Every finding of the chart that was not a mistake: active and resolved, oldest first. */
    List<ToothFinding> findHistoryByChart(UUID chartId);
    Optional<ToothFinding> findActiveByChartFdiAndCode(UUID chartId, String fdi, String findingCode);
    /** The active finding with exactly this surface; a null surface matches the whole-tooth one. */
    Optional<ToothFinding> findActiveByChartFdiCodeAndSurface(UUID chartId, String fdi, String findingCode, String surface);
    List<ToothFinding> findBySession(UUID sessionId);
}
