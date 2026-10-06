package com.orthoflow.patient.application.port;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Creates or completes a patient from what someone else collected — a receptionist
 * at the door, a booking form, a self-registration — without the caller depending
 * on the patient module's service layer.
 */
public interface PatientRegistrar {

    /** Everything a form can tell us. All optional but the names. */
    record Registration(String firstName, String lastName, String gender, LocalDate dateOfBirth, String phone, String email,
                        String address, String cin, String guardianName, String guardianPhone, String insuranceProvider,
                        String insuranceNumber, String occupation, String language, OffsetDateTime consentedAt) {
    }

    /** A walk-in's name and phone; the rest is completed later. */
    UUID registerWalkIn(String firstName, String lastName, String phone);

    /** A new patient. An email another patient already holds is left out rather than failing the registration. */
    UUID register(Registration registration);

    /** Fills the blanks of an existing patient from a registration; anything already recorded is kept. */
    void enrich(UUID patientId, Registration registration);
}
