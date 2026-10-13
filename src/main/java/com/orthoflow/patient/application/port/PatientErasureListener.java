package com.orthoflow.patient.application.port;

import java.util.UUID;

/**
 * Lets a module that keeps data about a patient outside the tables the
 * database cascades through clean it up when the patient is erased, without
 * {@code PatientService} depending on that module.
 *
 * <p>Called inside the erasure transaction, just before the patient row is
 * deleted, so a listener that fails rolls the whole erasure back rather than
 * leaving part of a person's data behind.
 *
 * <p>Needed because not every reference is a cascade: the voice audit trail
 * deliberately outlives its patient (an immutable record of who dictated what,
 * when), but what was said and written about that person is their data and
 * must not.
 */
public interface PatientErasureListener {

    void onPatientErased(UUID patientId);

    /**
     * The patient is being anonymised instead: the row stays, stripped of identity, because
     * financial records the law makes the clinic keep still point at it. Everything a listener
     * would remove on erasure is removed just the same, so this defaults to it.
     */
    default void onPatientAnonymised(UUID patientId) {
        onPatientErased(patientId);
    }
}
