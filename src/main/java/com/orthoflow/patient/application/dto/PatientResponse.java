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
@Builder
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
        String status,
        OffsetDateTime consentGivenAt,
        String consentNotes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

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
                .status(p.getStatus())
                .consentGivenAt(p.getConsentGivenAt())
                .consentNotes(p.getConsentNotes())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
