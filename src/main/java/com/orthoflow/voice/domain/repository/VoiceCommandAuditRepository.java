package com.orthoflow.voice.domain.repository;

import com.orthoflow.voice.domain.model.ConfirmationStatus;
import com.orthoflow.voice.domain.model.VoiceCommandAudit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoiceCommandAuditRepository {
    VoiceCommandAudit save(VoiceCommandAudit entry);
    Optional<VoiceCommandAudit> findById(UUID id);
    List<VoiceCommandAudit> findByPatient(UUID patientId);
    List<VoiceCommandAudit> findBySession(UUID sessionId);

    /**
     * Blanks everything in this patient's audit rows that says what was
     * dictated or written about them — the utterance, the resolved entities,
     * the before/after values and the error text — keeping only the skeleton
     * (who acted, when, which intent, how it ended). Returns the rows touched.
     */
    int scrubPatientData(UUID patientId);

    /**
     * Moves one row from {@code from} to {@code to} only if it is still in
     * {@code from}, as a single atomic step; true when this call made the
     * move. Two callers racing for the same PENDING command get exactly one
     * true between them, which is what stops a double Save or a double confirm
     * from writing the same clinical entry twice.
     */
    boolean transitionConfirmation(UUID id, ConfirmationStatus from, ConfirmationStatus to);
}
