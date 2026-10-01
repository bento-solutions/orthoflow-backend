package com.orthoflow.consultation.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the system believes the conversation established — a proposal for the
 * doctor to validate, correct or remove. Never the record.
 *
 * <p>Every claim carries the {@code quote} it came from, copied out of the
 * transcript. That is what lets the doctor check an item at a glance, and what
 * lets the server throw away anything a model made up: a quote that is not in
 * the transcript is not a claim about this conversation.
 *
 * <p>The list items carry a {@code key} that is stable from one extraction to
 * the next (the kind of thing plus what it is), so the browser can tell "the
 * same allergy, said again" from a new one and never resurrect an item the
 * doctor already removed.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ConsultationDraft(
        PatientFields patient,
        Quoted<String> chiefComplaint,
        List<ActiveTreatment> activeTreatments,
        List<Allergy> allergies,
        List<HistoryEntry> medicalHistory,
        List<PlanItem> treatmentPlan,
        NextAppointment nextAppointment,
        /** Who produced it: {@code rules}, or {@code vendor:model} when a model did. */
        String source) {

    /** A value and the words it was taken from. */
    public record Quoted<T>(T value, String quote) {
        public boolean present() {
            return value != null && !(value instanceof String s && s.isBlank());
        }
    }

    public record PatientFields(
            Quoted<String> firstName,
            Quoted<String> lastName,
            Quoted<Integer> age,
            /** ISO date, only when a full date of birth was said. */
            Quoted<String> dateOfBirth,
            /** {@code M} or {@code F}. */
            Quoted<String> gender,
            Quoted<String> phone,
            Quoted<String> cin,
            /** One of {@code CNOPS, CNSS, CNAM, RAMED, PRIVATE}. */
            Quoted<String> insuranceProvider,
            Quoted<String> insuranceNumber) {

        public static PatientFields empty() {
            return new PatientFields(null, null, null, null, null, null, null, null, null);
        }
    }

    /** Something the patient is going through now: a medicine, an appliance, a course of care. */
    public record ActiveTreatment(String key, String label, String detail,
                                  /** {@code MEDICATION}, {@code DENTAL} or {@code OTHER}. */
                                  String type, String quote) {}

    public record Allergy(String key, String substance, String reaction,
                          /** {@code MILD}, {@code MODERATE}, {@code SEVERE}, or null. */
                          String severity, String quote) {}

    public record HistoryEntry(String key,
                               /** A {@code MedicalHistoryCategory} name. */
                               String category, String label, String detail, String quote) {}

    /** One act the doctor proposed. */
    public record PlanItem(String key, String label,
                           /** The catalog treatment it matched, if any. */
                           UUID treatmentId, String treatmentCode,
                           /** FDI numbers, comma separated, if the doctor named teeth. */
                           String teeth,
                           BigDecimal price,
                           /** {@code SPOKEN} (the doctor said it) or {@code CATALOG} (the base price). */
                           String priceSource,
                           String notes, String quote) {}

    public record NextAppointment(
            /** ISO date, resolved on the server, never earlier than today. */
            String date,
            /** {@code HH:mm}. */
            String time,
            /** As said ("in 15 days"), kept for display next to the resolved date. */
            Integer inDays,
            String reason, String quote) {}

    public static ConsultationDraft empty(String source) {
        return new ConsultationDraft(PatientFields.empty(), null, List.of(), List.of(), List.of(), List.of(),
                null, source);
    }
}
