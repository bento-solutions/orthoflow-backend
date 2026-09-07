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
 * The fields a caller may change on an existing patient — the same demographic
 * set as {@link CreatePatientRequest}. Identity ({@code id}), audit columns
 * ({@code createdAt}, {@code version}), the soft-delete markers and the consent
 * timestamp are deliberately not here: they are not the caller's to set through
 * this endpoint.
 */
@Getter
@Setter
public class UpdatePatientRequest {

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

    @Pattern(regexp = "ACTIVE|COMPLETED|ON_HOLD|INACTIVE",
            message = "status must be one of ACTIVE, COMPLETED, ON_HOLD, INACTIVE")
    private String status;
}
