package com.orthoflow.prescription.application.dto;

import com.orthoflow.prescription.domain.model.Prescription;
import com.orthoflow.prescription.domain.model.PrescriptionTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PrescriptionDtos {

    private PrescriptionDtos() {
    }

    /** One drug of an ordonnance: its trade name, form, active substance (DCI) and how to take it. */
    @Schema(name = "PrescriptionLine")
    public record Line(@NotBlank @Size(max = 160) String drug, @Size(max = 80) String form, @Size(max = 160) String dci,
                       @NotBlank @Size(max = 600) String posology) {
    }

    /** A published reference prescription (ordonnance.ma), to read and adopt, never printed as-is. */
    @Schema(name = "PrescriptionLibraryEntry")
    public record LibraryEntry(String code, String name, String category, List<Line> lines, String advice,
                               String warningSigns, String alternative, String sourceUrl, boolean adopted) {
    }

    @Schema(name = "PrescriptionTemplateRequest")
    public record TemplateRequest(@NotBlank @Size(max = 160) String name, PrescriptionTemplate.Category category,
                                  @Valid @NotEmpty @Size(max = 20) List<Line> lines, @Size(max = 4000) String advice,
                                  Boolean active) {
    }

    @Schema(name = "PrescriptionTemplateView")
    public record TemplateView(UUID id, String name, PrescriptionTemplate.Category category, List<Line> lines, String advice,
                               String libraryCode, boolean reviewed, OffsetDateTime reviewedAt, String reviewedByName,
                               boolean active) {
    }

    /** A drug that may not suit this patient: which drug, which recorded allergy, and why they are related. */
    @Schema(name = "PrescriptionAllergyWarning")
    public record AllergyWarning(String drug, String allergy, String reason) {
    }

    @Schema(name = "PrescriptionCheckRequest")
    public record CheckRequest(@NotNull UUID patientId, @Valid @NotEmpty @Size(max = 20) List<Line> lines) {
    }

    /**
     * An ordonnance to issue. When the check finds a drug related to one of the patient's
     * allergies, issuing needs {@code acknowledgeWarnings}: the prescriber saw it and decided.
     */
    @Schema(name = "PrescriptionRequest")
    public record IssueRequest(@NotNull UUID patientId, UUID practitionerId, UUID consultationId, UUID templateId,
                               @Valid @NotEmpty @Size(max = 20) List<Line> lines, @Size(max = 4000) String advice,
                               boolean acknowledgeWarnings) {
    }

    @Schema(name = "PrescriptionView")
    public record View(UUID id, String number, UUID patientId, String patientName, UUID practitionerId,
                       String practitionerName, UUID consultationId, UUID templateId, List<Line> lines, String advice,
                       List<AllergyWarning> warnings, Prescription.Status status, OffsetDateTime issuedAt) {
    }
}
