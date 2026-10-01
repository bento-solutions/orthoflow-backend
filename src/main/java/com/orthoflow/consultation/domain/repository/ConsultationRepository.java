package com.orthoflow.consultation.domain.repository;

import com.orthoflow.consultation.domain.model.Consultation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultationRepository {
    Consultation save(Consultation consultation);

    Optional<Consultation> findById(UUID id);

    /** Newest first. */
    List<Consultation> findByPatient(UUID patientId);

    /** The consultation for this patient that was neither saved nor thrown away, if any. */
    Optional<Consultation> findOpenByPatient(UUID patientId);

    /**
     * Stores the doctor's review state on an open consultation, without moving
     * its version: it must not turn a concurrent reading or save into a conflict.
     *
     * @return false when the consultation is no longer open (or does not exist)
     */
    boolean saveReviewState(UUID id, String reviewState);

    /** Open consultations nothing has happened on since {@code before}. */
    List<Consultation> findIdleOpen(OffsetDateTime before);
}
