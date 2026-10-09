package com.orthoflow.patient.application.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The published port other modules use to read patient data instead of
 * holding a {@code @ManyToOne Patient} JPA relation into this module's
 * entity graph (audit I.2). {@code scheduling} and {@code stock} depend on
 * this interface, never on {@code patient.domain.model.Patient} directly.
 */
public interface PatientLookup {

    Optional<PatientSummary> findSummary(UUID patientId);

    /** Everything a printed document needs to say about a patient: a fee note, an insurance form. */
    Optional<PatientIdentity> findIdentity(UUID patientId);

    /**
     * Batched form of {@link #findSummary(UUID)} — used when mapping a list
     * of records (e.g. a day's appointments) to their response DTOs, so
     * enriching each row with a patient name doesn't reintroduce the
     * per-row N+1 lookup the audit already flagged (II.9).
     */
    Map<UUID, PatientSummary> findSummaries(List<UUID> patientIds);

    boolean exists(UUID patientId);

    /**
     * The patient whose phone ends with the same nine digits as {@code digits}
     * (a number as WhatsApp reports it, whatever formatting the clinic typed).
     * Empty when none or when more than one patient shares the number, since a
     * guess there could attach a reply to the wrong person.
     */
    Optional<UUID> findIdByPhoneDigits(UUID practiceId, String digits);

    /** The practitioner a patient is assigned to by default, when they have one. */
    Optional<UUID> findPrimaryPractitionerId(UUID patientId);
}
