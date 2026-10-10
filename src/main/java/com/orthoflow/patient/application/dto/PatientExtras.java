package com.orthoflow.patient.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The front-desk fields that came after the original form: the patient's code,
 * photo, occupation, how they heard of the clinic, a standing discount, the
 * language they want to be written to in, their insurer, their usual
 * practitioner and any extra phone numbers. Shared by create and update.
 */
@Getter
@Setter
public abstract class PatientExtras {

    /** Typed by staff to keep an existing numbering scheme; blank means generate the next one. */
    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._/-]*$", message = "the code may contain letters, digits and . _ / -")
    private String patientCode;

    @Size(max = 150)
    private String occupation;

    @Size(max = 120)
    private String referralSource;

    @DecimalMin("0.00")
    @DecimalMax("100.00")
    private BigDecimal globalDiscountPct;

    @Pattern(regexp = "fr|en|ar", message = "language must be fr, en or ar")
    private String preferredLanguage;

    private UUID insurerId;

    @Size(max = 50)
    private String insuranceAffiliationNumber;

    /** Who the patient is to the insured person; the forms tick "conjoint" or "enfant" from it. */
    @Pattern(regexp = "SELF|SPOUSE|CHILD", message = "insuredRelation must be SELF, SPOUSE or CHILD")
    private String insuredRelation;

    /** The insured person, when the patient is their spouse or child. */
    @Size(max = 255)
    private String insuredName;

    @Size(max = 50)
    private String insuredCin;

    private UUID primaryPractitionerId;

    private UUID photoFileId;

    /** Replaces the patient's extra numbers when present; omit to leave them. */
    @Valid
    private List<PhoneEntry> phones;

    public record PhoneEntry(@NotBlank @Size(max = 40) String number, @Size(max = 40) String label) {
    }
}
