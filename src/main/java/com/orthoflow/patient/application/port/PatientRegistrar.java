package com.orthoflow.patient.application.port;

import java.util.UUID;

/**
 * Registers a patient from the little a receptionist knows at the door — a
 * walk-in's name and phone. A port so scheduling can do it without depending on
 * the patient module's service layer; the full record is completed later.
 */
public interface PatientRegistrar {

    UUID registerWalkIn(String firstName, String lastName, String phone);
}
