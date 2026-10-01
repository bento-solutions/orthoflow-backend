package com.orthoflow.patient.application.dto;

import java.time.LocalDate;

/**
 * A partial change to who a patient is: a null field is left as it is.
 *
 * <p>{@link UpdatePatientRequest} replaces every field and requires both
 * names, which is right for the edit form and wrong for a consultation that
 * learned one phone number. This is what the consultation save uses, so adding
 * a phone cannot wipe the address that was typed in at the front desk.
 */
public record PatientDemographicsUpdate(
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String gender,
        String phone,
        String cin,
        String insuranceProvider,
        String insuranceNumber) {

    public boolean isEmpty() {
        return firstName == null && lastName == null && dateOfBirth == null && gender == null
                && phone == null && cin == null && insuranceProvider == null && insuranceNumber == null;
    }
}
