package com.orthoflow.patient.application.dto;

import com.orthoflow.patient.domain.model.Patient;
import lombok.Builder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What the API returns for a patient. Everything the frontend reads, and
 * nothing it doesn't: the entity's {@code version} and the {@code deletedAt}/
 * {@code deletedBy} soft-delete markers are internal and are not serialised.
 */
@Builder(toBuilder = true)
public record PatientResponse(
        UUID id,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String gender,
        String email,
        String phone,
        String address,
        String cin,
        String guardianName,
        String guardianPhone,
        String insuranceProvider,
        String insuranceNumber,
        String insuranceAffiliationNumber,
        String insuredRelation,
        String insuredName,
        String insuredCin,
        String status,
        OffsetDateTime consentGivenAt,
        String consentNotes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String patientCode,
        UUID photoFileId,
        String occupation,
        String referralSource,
        java.math.BigDecimal globalDiscountPct,
        String preferredLanguage,
        UUID insurerId,
        UUID primaryPractitionerId,
        java.util.List<PatientExtras.PhoneEntry> phones) {

    public static PatientResponse from(Patient p) {
        return PatientResponse.builder()
                .id(p.getId())
                .firstName(p.getFirstName())
                .lastName(p.getLastName())
                .dateOfBirth(p.getDateOfBirth())
                .gender(p.getGender())
                .email(p.getEmail())
                .phone(p.getPhone())
                .address(p.getAddress())
                .cin(p.getCin())
                .guardianName(p.getGuardianName())
                .guardianPhone(p.getGuardianPhone())
                .insuranceProvider(p.getInsuranceProvider())
                .insuranceNumber(p.getInsuranceNumber())
                .insuranceAffiliationNumber(p.getInsuranceAffiliationNumber())
                .insuredRelation(p.getInsuredRelation())
                .insuredName(p.getInsuredName())
                .insuredCin(p.getInsuredCin())
                .status(p.getStatus())
                .consentGivenAt(p.getConsentGivenAt())
                .consentNotes(p.getConsentNotes())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .patientCode(p.getPatientCode())
                .photoFileId(p.getPhotoFileId())
                .occupation(p.getOccupation())
                .referralSource(p.getReferralSource())
                .globalDiscountPct(p.getGlobalDiscountPct())
                .preferredLanguage(p.getPreferredLanguage())
                .insurerId(p.getInsurerId())
                .primaryPractitionerId(p.getPrimaryPractitionerId())
                .phones(java.util.List.of())
                .build();
    }

    /** The same patient with their extra phone numbers, which a plain entity read does not carry. */
    public PatientResponse withPhones(java.util.List<PatientExtras.PhoneEntry> numbers) {
        return toBuilder().phones(numbers).build();
    }
}
