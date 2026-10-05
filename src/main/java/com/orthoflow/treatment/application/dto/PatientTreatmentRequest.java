package com.orthoflow.treatment.application.dto;

import com.orthoflow.treatment.domain.model.PatientTreatmentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating/updating a patient treatment. Deliberately
 * excludes `id`, `patient`, `stockMovementsGenerated`, `createdBy` — those
 * are server-owned. Binding the entity directly (the previous behaviour) let
 * a client set `stockMovementsGenerated: true` on create, which made
 * deductStock() no-op and recorded a completed treatment with no inventory
 * movement and no trace in the audit ledger (see audit V.6, exploit A).
 */
@Getter
@Setter
public class PatientTreatmentRequest {

    @NotNull
    private UUID treatmentId;

    @NotBlank
    private String teeth;

    @NotNull
    private PatientTreatmentStatus status;

    @Min(0)
    private int progress;

    private String notes;

    private String doctorName;

    private UUID practitionerId;

    private LocalDate startDate;

    private LocalDate endDate;

    @Valid
    private List<PatientTreatmentConsumableRequest> consumables;
}
