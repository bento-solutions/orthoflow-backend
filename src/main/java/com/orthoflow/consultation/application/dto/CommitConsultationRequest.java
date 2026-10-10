package com.orthoflow.consultation.application.dto;

import com.orthoflow.voice.application.dto.CommitVoiceSessionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What the doctor decided at review — everything they ticked, corrected or
 * typed, and nothing else. The only way a consultation reaches the record.
 *
 * <p>These are the doctor's values, not the extraction's: the draft they were
 * shown never travels back, so an item they removed cannot return and an item
 * they never saw cannot slip in. Chart findings dictated in the examination
 * phase are named by audit id, exactly as in a dictated examination.
 */
@Getter
@Setter
public class CommitConsultationRequest {

    /** Fields the consultation learned about the patient. A null field is left unchanged. */
    @Valid
    private PatientChanges patient;

    @Valid
    @Size(max = 50)
    private List<AllergyItem> allergies = List.of();

    @Valid
    @Size(max = 50)
    private List<HistoryItem> medicalHistory = List.of();

    @Valid
    @Size(max = 50)
    private List<ActiveTreatmentItem> activeTreatments = List.of();

    @Size(max = 500)
    private String chiefComplaint;

    @Valid
    @Size(max = 50)
    private List<PlanLine> treatmentPlan = List.of();

    @Valid
    private AppointmentSlot nextAppointment;

    /** The consultation report as the doctor edited it. */
    @Size(max = 30_000)
    private String report;

    // ── Chart findings (dictated in the examination phase) ──────────────

    @NotNull
    private List<UUID> approvedAuditIds = List.of();

    @NotNull
    private List<UUID> rejectedAuditIds = List.of();

    @Valid
    @NotNull
    private List<CommitVoiceSessionRequest.Amendment> amendments = List.of();

    // ── Items ───────────────────────────────────────────────────────────

    @Getter
    @Setter
    public static class PatientChanges {
        @Size(max = 255)
        private String firstName;
        @Size(max = 255)
        private String lastName;
        @Past
        private LocalDate dateOfBirth;
        @Pattern(regexp = "[MFmf]", message = "gender must be 'M' or 'F'")
        private String gender;
        @Size(max = 50)
        private String phone;
        @Size(max = 50)
        private String cin;
        @Size(max = 100)
        private String insuranceProvider;
        @Size(max = 100)
        private String insuranceNumber;
    }

    @Getter
    @Setter
    public static class AllergyItem {
        @NotBlank
        @Size(max = 160)
        private String substance;
        @Size(max = 500)
        private String reaction;
        @Pattern(regexp = "MILD|MODERATE|SEVERE", message = "severity must be MILD, MODERATE or SEVERE")
        private String severity;
    }

    @Getter
    @Setter
    public static class HistoryItem {
        @NotBlank
        @Pattern(regexp = "CONDITION|MEDICATION|SURGERY|DENTAL_HISTORY|FAMILY|LIFESTYLE|OTHER")
        private String category;
        @NotBlank
        @Size(max = 160)
        private String label;
        @Size(max = 500)
        private String detail;
    }

    @Getter
    @Setter
    public static class ActiveTreatmentItem {
        @NotBlank
        @Size(max = 160)
        private String label;
        @Size(max = 500)
        private String detail;
        @Pattern(regexp = "MEDICATION|DENTAL|OTHER")
        private String type = "OTHER";
    }

    @Getter
    @Setter
    public static class PlanLine {
        @NotBlank
        @Size(max = 200)
        private String label;
        /** The catalog treatment, if the doctor linked one. */
        private UUID treatmentId;
        @Size(max = 100)
        private String teeth;
        @PositiveOrZero
        private BigDecimal price;
        @Min(1)
        private Integer quantity = 1;
        @Size(max = 500)
        private String notes;
        /**
         * Done during this session rather than proposed. Done acts go on the insurer's
         * care form as executed; proposed orthodontic and prosthetic acts go on a
         * prior-agreement request.
         */
        private Boolean performed = false;
    }

    @Getter
    @Setter
    public static class AppointmentSlot {
        @NotNull
        private OffsetDateTime dateTime;
        @Min(5)
        private Integer durationMinutes;
        @Size(max = 100)
        private String type;
        private UUID chairId;
        @Size(max = 500)
        private String notes;
    }
}
