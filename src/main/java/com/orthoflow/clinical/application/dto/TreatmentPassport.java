package com.orthoflow.clinical.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What a patient carries to another practitioner: who they are, what to watch
 * out for, the state of the teeth, the work already done (and by whom), what is
 * still to do, and the gums.
 *
 * <p>It is the machine-readable copy of the printed passport and carries codes,
 * never words in a language: findings by their catalog code, surfaces by name,
 * dates in ISO form, teeth in FDI notation. {@link #format} and {@link #version}
 * let a reader refuse what it does not understand. It deliberately leaves out
 * contact details, identity numbers, insurance, invoices and free-text clinical
 * notes: the passport is for the next dentist, not for the next clerk.
 */
@Schema(name = "TreatmentPassport")
public record TreatmentPassport(
        String format,
        int version,
        OffsetDateTime issuedAt,
        PassportClinic clinic,
        PassportPatient patient,
        List<PassportAllergy> allergies,
        List<PassportHistoryItem> medicalHistory,
        List<PassportFinding> teeth,
        List<PassportWork> planned,
        List<PassportWork> log,
        List<PassportGum> gums,
        PassportChart chart
) {

    public static final String FORMAT = "orthoflow.treatment-passport";
    public static final int VERSION = 1;

    @Schema(name = "PassportClinic")
    public record PassportClinic(String name, String city, String phone, String email) {}

    @Schema(name = "PassportPatient")
    public record PassportPatient(String firstName, String lastName, LocalDate dateOfBirth, String sex) {}

    @Schema(name = "PassportAllergy")
    public record PassportAllergy(String substance, String reaction, String severity) {}

    @Schema(name = "PassportHistoryItem")
    public record PassportHistoryItem(String category, String label, String detail) {}

    /** One thing true of one tooth now. */
    @Schema(name = "PassportFinding")
    public record PassportFinding(String fdi, String findingCode, String kind, List<String> surfaces,
                                  String severity, String note, LocalDate performedOn, String origin,
                                  String provider) {}

    /**
     * One piece of work: a finding that records treatment, or a treatment of the
     * clinic's own. {@code outcome} is IN_PLACE, TREATED or COMPLETED in the log
     * and REQUIRED, PLANNED or IN_PROGRESS among the work still to do.
     */
    @Schema(name = "PassportWork")
    public record PassportWork(LocalDate date, String type, List<String> teeth, String code, String name,
                               String actCode, List<String> surfaces, String origin, String provider,
                               String outcome, String note) {}

    @Schema(name = "PassportGum")
    public record PassportGum(String region, String condition, Integer stage, LocalDate assessedOn, String note) {}

    /**
     * The dental chart and its legend, as printed. Each thing recorded has a line in the
     * legend and a letter ({@code key}); a tooth carries the letters of what concerns it,
     * so the drawing and the legend can be read against each other, in greyscale too.
     *
     * <p>{@code dentition} is adult or child.
     */
    @Schema(name = "PassportChart")
    public record PassportChart(String dentition, List<PassportChartTooth> teeth, List<PassportChartEntry> legend) {}

    /**
     * A tooth with something on it. {@code done} says treatment has been administered on it;
     * {@code owed} says some is still to come.
     */
    @Schema(name = "PassportChartTooth")
    public record PassportChartTooth(String fdi, String status, List<String> keys, boolean done, boolean owed) {}

    /**
     * One line of the legend: one kind of finding or one treatment of the clinic's catalogue.
     *
     * <p>{@code group} is DONE (administered), CONDITION, PLANNED, WATCH or ABSENT.
     * {@code symbol} is how the chart draws it: hatch, solid, endo, dotted, stipple, jag, bars,
     * cross, dot, ring or dashed. {@code outcome} is as in {@link PassportWork}, and for a
     * condition or absence, ACTIVE.
     */
    @Schema(name = "PassportChartEntry")
    public record PassportChartEntry(String key, String group, String symbol, String code, String name,
                                     String actCode, String outcome, List<PassportChartUse> uses) {}

    /** Where and when: {@code teeth} is empty for work on the whole mouth, such as scaling. */
    @Schema(name = "PassportChartUse")
    public record PassportChartUse(List<String> teeth, List<String> surfaces, LocalDate date, String origin,
                                   String provider) {}
}
