package com.orthoflow.clinical.domain.repository;

import com.orthoflow.clinical.domain.model.PerioChart;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PerioChartRepository {

    /** Saves and flushes, so the returned chart already carries its new version. */
    PerioChart save(PerioChart chart);

    /** A chart of this patient, deleted or not. */
    Optional<PerioChart> findOfPatient(UUID patientId, UUID chartId);

    /** The patient's charts that were not deleted, most recent examination first. */
    List<PerioChart> findAlive(UUID patientId);

    /** The patient's deleted charts, most recently deleted first. */
    List<PerioChart> findDeleted(UUID patientId);
}
