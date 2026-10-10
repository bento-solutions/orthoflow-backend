package com.orthoflow.insurance.application.dto;

import com.orthoflow.insurance.domain.model.InsuranceForm;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class InsuranceFormDtos {

    private InsuranceFormDtos() {
    }

    /** One act as the form lists it. {@code code} is the insurer act code (D629, C); {@code cotation} the key letter and coefficient. */
    @Schema(name = "InsuranceFormLine")
    public record Line(LocalDate date, String teeth, String code, String label, String cotation, BigDecimal amount) {
    }

    /** The person the insurer covers: the patient, or their parent or spouse. */
    @Schema(name = "InsuranceFormInsured")
    public record Insured(String fullName, String cin, String immatriculation, String affiliation, String address,
                          String phone) {
    }

    /** The person who was treated. */
    @Schema(name = "InsuranceFormBeneficiary")
    public record Beneficiary(String fullName, String cin, LocalDate dateOfBirth, String sex) {
    }

    /** Everything printed on a form, kept as it was printed. */
    @Schema(name = "InsuranceFormData")
    public record FormData(String insurerName, String insurerCode, String formCode, String formName,
                           InsuranceForm.Purpose purpose, LocalDate careDate, Insured insured, Beneficiary beneficiary,
                           String relation, String practitionerName, String practitionerInpe, String clinicName,
                           String clinicAddress, String clinicCity, String clinicPhone, String agreementNumber,
                           List<Line> lines, BigDecimal total) {
    }

    /** A line typed at the front desk, or picked from the catalogue (its insurer code and cotation are then filled in). */
    @Schema(name = "InsuranceFormLineRequest")
    public record LineRequest(LocalDate date, @Size(max = 100) String teeth, UUID treatmentId, @Size(max = 30) String code,
                              @Size(max = 200) String label, @Size(max = 30) String cotation,
                              @PositiveOrZero BigDecimal amount) {
    }

    /**
     * A form made by hand. With {@code send}, it goes to the front desk as a task:
     * to {@code assigneeId} when named, else to everyone holding the assistant role.
     */
    @Schema(name = "InsuranceFormRequest")
    public record CreateRequest(@NotNull UUID patientId, @NotNull InsuranceForm.Purpose purpose, LocalDate careDate,
                                UUID practitionerId, @Valid @NotEmpty @Size(max = 30) List<LineRequest> lines,
                                @Size(max = 30) String agreementNumber, @Size(max = 2000) String notes,
                                boolean send, UUID assigneeId) {
    }

    @Schema(name = "InsuranceFormSendRequest")
    public record SendRequest(UUID assigneeId, @Size(max = 1000) String message) {
    }

    @Schema(name = "InsuranceFormView")
    public record View(UUID id, String number, UUID patientId, String patientName, UUID insurerId, String insurerName,
                       String formCode, String formName, boolean official, boolean singleUse,
                       InsuranceForm.Purpose purpose, InsuranceForm.Source source, UUID consultationId, LocalDate careDate,
                       List<Line> lines, BigDecimal total, InsuranceForm.Status status, UUID taskId,
                       OffsetDateTime printedAt, OffsetDateTime handedOverAt, OffsetDateTime createdAt, String notes,
                       List<String> missing) {
    }

    /** A form OrthoFlow can fill, and the insurers it is used for unless a clinic says otherwise. */
    @Schema(name = "InsuranceFormLayoutView")
    public record LayoutView(String code, String name, List<String> insurerCodes, boolean official, boolean singleUse,
                             String source, String note) {
    }

    /**
     * What would be printed for a patient today: which form, and what the record is
     * missing for it (no insurer, no immatriculation...), so it can be fixed first.
     */
    @Schema(name = "InsuranceFormPreview")
    public record Preview(UUID insurerId, String insurerName, String formCode, String formName, boolean official,
                          boolean singleUse, List<String> missing) {
    }

    /** A form a consultation produced, as the consultation's save reports it. */
    @Schema(name = "InsuranceFormIssued")
    public record Issued(UUID id, String number, String insurerName, String formName, InsuranceForm.Purpose purpose,
                         int lines, BigDecimal total, boolean sent) {
    }
}
