package com.orthoflow.patient.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deleting a patient through the normal product flow archives it
 * (deleted_at/deleted_by set) rather than removing the row — a real DELETE
 * used to cascade to appointments and treatment history with no retention
 * path (audit II.15). {@code @SQLRestriction} hides archived patients from
 * every normal query; a genuinely separate, ADMIN-only erasure endpoint
 * performs the actual hard delete for GDPR/Law 09-08 requests.
 */
@Entity
@Table(name = "patients")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Patient {

    @Id
    private UUID id;

    // Internal columns: never part of the API surface. PatientController
    // returns PatientResponse (which omits them); these @JsonIgnores also
    // cover any other path that serialises the entity, e.g. the ADMIN
    // consent endpoint in the compliance module (audit H3).
    @JsonIgnore
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 1)
    private String gender;

    @Column(unique = true)
    private String email;

    private String phone;

    @Column(columnDefinition = "TEXT")
    private String address;

    private String cin;

    @Column(name = "guardian_name")
    private String guardianName;

    @Column(name = "guardian_phone")
    private String guardianPhone;

    @Column(name = "insurance_provider")
    private String insuranceProvider;

    @Column(name = "insurance_number")
    private String insuranceNumber;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @JsonIgnore
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @JsonIgnore
    @Column(name = "deleted_by")
    private UUID deletedBy;

    /**
     * When the patient (or their guardian, for a minor) consented to their
     * health data being processed — the recorded legal basis GDPR Art. 6/7
     * requires and this product previously had no way to capture (audit
     * V.8). Null means "not yet captured," not "consent refused" — the UI
     * should prompt for it, not assume either way.
     */
    @Column(name = "consent_given_at")
    private OffsetDateTime consentGivenAt;

    @Column(name = "consent_notes", columnDefinition = "TEXT")
    private String consentNotes;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
