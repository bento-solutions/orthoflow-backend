package com.orthoflow.patient.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * The fields a caller may set when registering a patient.
 *
 * <p>{@code PatientController} used to bind the {@code Patient} JPA entity
 * straight from the request body, so a caller could also send {@code id}
 * (overwriting another patient's row), {@code deletedAt} (a hidden record),
 * {@code consentGivenAt} (a fabricated GDPR consent timestamp), {@code version},
 * or {@code createdAt}. This DTO is the whitelist — nothing outside it reaches
 * the entity — and it finally applies the same field validation every other
 * create endpoint in the app already had.
 */
@Getter
@Setter
public class CreatePatientRequest {

    @NotBlank
    @Size(max = 255)
    private String firstName;

    @NotBlank
    @Size(max = 255)
    private String lastName;

    @Past
    private LocalDate dateOfBirth;

    @Pattern(regexp = "[MFmf]", message = "gender must be 'M' or 'F'")
    private String gender;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 50)
    private String phone;

    @Size(max = 5000)
    private String address;

    @Size(max = 50)
    private String cin;

    @Size(max = 255)
    private String guardianName;

    @Size(max = 50)
    private String guardianPhone;

    @Size(max = 100)
    private String insuranceProvider;

    @Size(max = 100)
    private String insuranceNumber;

    /**
     * Optional; defaults to ACTIVE. Archival ("deleted") is done through the
     * delete endpoint, not by setting a status here.
     */
    @Pattern(regexp = "ACTIVE|COMPLETED|ON_HOLD|INACTIVE",
            message = "status must be one of ACTIVE, COMPLETED, ON_HOLD, INACTIVE")
    private String status;
}
