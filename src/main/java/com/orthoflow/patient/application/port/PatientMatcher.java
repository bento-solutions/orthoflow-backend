package com.orthoflow.patient.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Existing patients that may be the person on a form — so staff merge into a file instead of making a second one. */
public interface PatientMatcher {

    record Candidate(UUID id, String patientCode, String fullName, LocalDate dateOfBirth, String phone, String cin, String reason) {
    }

    List<Candidate> candidates(UUID practiceId, String firstName, String lastName, LocalDate dateOfBirth, String phone, String cin);
}
