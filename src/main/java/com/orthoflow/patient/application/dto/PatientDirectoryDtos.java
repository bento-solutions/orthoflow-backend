package com.orthoflow.patient.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class PatientDirectoryDtos {

    private PatientDirectoryDtos() {
    }

    public record Row(UUID id, String patientCode, String firstName, String lastName, String gender,
                      LocalDate dateOfBirth, Integer age, String phone, String email, String status,
                      String insurerName, UUID primaryPractitionerId, String primaryPractitionerName,
                      int progress, OffsetDateTime nextAppointment, OffsetDateTime lastVisit,
                      UUID photoFileId, OffsetDateTime createdAt) {
    }

    public record Kpis(long total, long newThisMonth, long male, long female, long otherGender, Double averageAge) {
    }

    public record DuplicatePair(Person first, Person second, String reason, double score) {
    }

    public record Person(UUID id, String patientCode, String firstName, String lastName, LocalDate dateOfBirth,
                         String phone, String cin, String email, OffsetDateTime createdAt) {
    }

    public record InsurerRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 150) String name,
                                 @Pattern(regexp = "PUBLIC|PRIVATE") String kind, Boolean active) {
    }

    public record InsurerResponse(UUID id, String code, String name, String kind, boolean active) {
    }

    public record ReferralSourceRequest(@NotBlank @Size(max = 120) String name, Boolean active, Integer displayOrder) {
    }

    public record ReferralSourceResponse(UUID id, String name, boolean active, int displayOrder) {
    }
}
