package com.orthoflow.patient.application.port;

import java.util.UUID;

/**
 * The narrow, read-only view of a patient that other modules are allowed
 * to depend on (audit I.2 / docs/adr/0001-patient-in-billing.md). Carries
 * only the fields other modules actually render today — id/name for
 * scheduling and stock display, email/cin for the same reason in the stock
 * module's patient-facing lists. Anything else a module needs should be a
 * deliberate addition here, not a reason to reach for the full
 * {@code Patient} entity or a new cross-module JPA relation.
 */
public record PatientSummary(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String phone,
        String cin,
        String preferredLanguage) {

    public String fullName() {
        return firstName + " " + lastName;
    }
}
