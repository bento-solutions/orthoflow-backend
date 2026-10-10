package com.orthoflow.patient.application.port;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The patient as a printed document names them. Wider than {@link PatientSummary},
 * which carries only what lists and calendars render; kept separate so adding a
 * line to a form never widens what every screen is handed.
 */
public record PatientIdentity(UUID id, String code, String firstName, String lastName, LocalDate dateOfBirth,
                              String gender, String cin, String address, String phone, String email,
                              String insuranceProvider, String insuranceNumber, UUID insurerId, String guardianName,
                              String insuranceAffiliationNumber, String insuredRelation, String insuredName, String insuredCin) {

    public String fullName() {
        return firstName + " " + lastName;
    }
}
