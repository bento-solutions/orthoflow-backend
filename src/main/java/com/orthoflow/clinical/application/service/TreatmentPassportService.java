package com.orthoflow.clinical.application.service;

import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.clinical.application.dto.AllergyResponse;
import com.orthoflow.clinical.application.dto.MedicalHistoryResponse;
import com.orthoflow.clinical.application.dto.PeriodontalAssessmentResponse;
import com.orthoflow.clinical.application.dto.ToothFindingResponse;
import com.orthoflow.clinical.application.dto.TreatmentPassport;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportAllergy;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportClinic;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportFinding;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportGum;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportHistoryItem;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportPatient;
import com.orthoflow.clinical.application.dto.TreatmentPassport.PassportWork;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.patient.application.port.PatientIdentity;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.treatment.domain.model.PatientTreatment;
import com.orthoflow.treatment.domain.repository.PatientTreatmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The patient's treatment passport: what another practitioner needs to take
 * over their care, assembled from the record this clinic already keeps.
 *
 * <p>Nothing is stored. It is derived every time from the findings, gum
 * assessments, allergies, history and treatments, so it cannot drift from the
 * chart; and the two copies a doctor can hand over (the PDF and the JSON) are
 * recorded in the activity log, because they carry a patient's clinical data
 * out of the system.
 */
@Service
@RequiredArgsConstructor
public class TreatmentPassportService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ClinicalRecordService clinical;
    private final PeriodontalService periodontal;
    private final PatientLookup patients;
    private final LetterheadProvider letterheads;
    private final PatientTreatmentRepository treatments;
    private final PdfService pdf;
    private final ActivityLog activity;

    /** The passport as data, for the screen. Not recorded: nothing left the system. */
    @Transactional(readOnly = true)
    public TreatmentPassport build(UUID practiceId, UUID patientId) {
        PatientIdentity patient = patients.findIdentity(patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));
        Letterhead letterhead = letterheads.forPractice(practiceId);

        List<ToothFindingResponse> history = clinical.listFindingHistory(patientId);
        List<PassportFinding> teeth = new ArrayList<>();
        List<PassportWork> planned = new ArrayList<>();
        List<PassportWork> log = new ArrayList<>();

        for (ToothFindingResponse f : history) {
            boolean active = "ACTIVE".equals(f.status());
            List<String> surfaces = surfaces(f.surface());
            if (active && "TREATMENT_REQUIRED".equals(f.kind())) {
                planned.add(new PassportWork(null, "FINDING", List.of(f.fdi()), f.findingCode(), null, null,
                        surfaces, f.origin(), f.providerName(), "REQUIRED", f.note()));
                continue;
            }
            if (active) {
                teeth.add(new PassportFinding(f.fdi(), f.findingCode(), f.kind(), surfaces, f.severity(), f.note(),
                        f.performedOn(), f.origin(), f.providerName()));
            }
            // The log is work: what stands in the mouth, and what was found and since treated.
            if (active && "EXISTING".equals(f.kind())) {
                log.add(new PassportWork(f.performedOn(), "FINDING", List.of(f.fdi()), f.findingCode(), null, null,
                        surfaces, f.origin(), f.providerName(), "IN_PLACE", f.note()));
            } else if ("RESOLVED".equals(f.status())) {
                LocalDate treatedOn = f.performedOn() != null ? f.performedOn()
                        : f.updatedAt() == null ? null : f.updatedAt().toLocalDate();
                log.add(new PassportWork(treatedOn, "FINDING", List.of(f.fdi()), f.findingCode(), null, null,
                        surfaces, f.origin(), f.providerName(), "TREATED", f.note()));
            }
        }

        for (PatientTreatment t : treatments.findByPatientId(patientId)) {
            List<String> teethOf = t.getTeeth() == null || t.getTeeth().isBlank() ? List.<String>of()
                    : Arrays.stream(t.getTeeth().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            String name = t.getTreatment().getName();
            String act = t.getTreatment().getActCode();
            switch (t.getStatus()) {
                case COMPLETED -> log.add(new PassportWork(t.getEndDate() != null ? t.getEndDate() : t.getStartDate(),
                        "TREATMENT", teethOf, null, name, act, List.of(), "THIS_CLINIC", t.getDoctorName(),
                        "COMPLETED", t.getNotes()));
                case ACTIVE -> planned.add(new PassportWork(t.getStartDate(), "TREATMENT", teethOf, null, name, act,
                        List.of(), "THIS_CLINIC", t.getDoctorName(), "IN_PROGRESS", t.getNotes()));
                case PLANNED -> planned.add(new PassportWork(t.getStartDate(), "TREATMENT", teethOf, null, name, act,
                        List.of(), "THIS_CLINIC", t.getDoctorName(), "PLANNED", t.getNotes()));
                default -> { }
            }
        }

        // Newest first, work of unknown date last: the order a practitioner reads it in.
        Comparator<PassportWork> newestFirst = Comparator
                .comparing(PassportWork::date, Comparator.nullsLast(Comparator.reverseOrder()));
        log.sort(newestFirst);
        teeth.sort(Comparator.comparing(PassportFinding::fdi));

        List<AllergyResponse> allergies = clinical.listAllergies(patientId);
        List<MedicalHistoryResponse> medical = clinical.listMedicalHistory(patientId);
        List<PeriodontalAssessmentResponse> gums = periodontal.status(patientId).current();

        return new TreatmentPassport(
                TreatmentPassport.FORMAT, TreatmentPassport.VERSION, OffsetDateTime.now(),
                new PassportClinic(letterhead.name(), letterhead.city(), letterhead.phone(), letterhead.email()),
                new PassportPatient(patient.firstName(), patient.lastName(), patient.dateOfBirth(), patient.gender()),
                allergies.stream().map(a -> new PassportAllergy(a.substance(), a.reaction(), a.severity())).toList(),
                medical.stream().map(m -> new PassportHistoryItem(m.category(), m.label(), m.detail())).toList(),
                teeth, planned, log,
                gums.stream().map(g -> new PassportGum(g.region(), g.condition(), g.stage(), g.assessedOn(), g.note())).toList());
    }

    /** The machine-readable copy a doctor hands over. Recorded in the activity log. */
    @Transactional
    public TreatmentPassport export(UUID practiceId, UUID patientId) {
        TreatmentPassport passport = build(practiceId, patientId);
        activity.record(practiceId, "PATIENT", patientId, "PASSPORT_EXPORTED", Map.of("format", "json"));
        return passport;
    }

    /** The printed copy, in the language the doctor picks. Recorded in the activity log. */
    @Transactional
    public byte[] pdf(UUID practiceId, UUID patientId, String lang) {
        TreatmentPassport passport = build(practiceId, patientId);
        TreatmentPassportLabels labels = TreatmentPassportLabels.of(lang);
        Letterhead letterhead = letterheads.forPractice(practiceId);
        byte[] bytes = pdf.render("treatment-passport", model(passport, letterhead, labels), labels.language());
        activity.record(practiceId, "PATIENT", patientId, "PASSPORT_EXPORTED",
                Map.of("format", "pdf", "language", labels.language()));
        return bytes;
    }

    // ── The printed view ────────────────────────────────────────────────

    /** Everything the template shows, already in words of the chosen language. */
    Map<String, Object> model(TreatmentPassport p, Letterhead letterhead, TreatmentPassportLabels l) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", letterhead);
        Map<String, String> text = new LinkedHashMap<>();
        for (String key : List.of("title", "intro", "patient", "birthDate", "gender", "issuedOn", "alerts", "noAlerts",
                "teeth", "noTeeth", "tooth", "state", "log", "noLog", "date", "work", "doneBy", "planned", "noPlanned",
                "gums", "noGums", "area", "legend", "consent", "machineCopy", "signature")) {
            text.put(key, l.text(key));
        }
        model.put("t", text);
        model.put("patientName", p.patient().firstName() + " " + p.patient().lastName());
        model.put("birthDate", p.patient().dateOfBirth() == null ? null : DAY.format(p.patient().dateOfBirth()));
        model.put("sex", "M".equals(p.patient().sex()) ? l.text("male") : "F".equals(p.patient().sex()) ? l.text("female") : null);
        model.put("issuedOn", DAY.format(p.issuedAt()));

        List<String> alerts = new ArrayList<>();
        for (PassportAllergy a : p.allergies()) {
            alerts.add(join(" — ", a.substance(), a.reaction()));
        }
        for (PassportHistoryItem h : p.medicalHistory()) {
            alerts.add(join(" — ", h.label(), h.detail()));
        }
        model.put("alerts", alerts);

        boolean rtl = "ar".equals(l.language());

        // One row per tooth, one line per thing true of it.
        Map<String, List<String>> byTooth = new LinkedHashMap<>();
        for (PassportFinding f : p.teeth()) {
            byTooth.computeIfAbsent(f.fdi(), k -> new ArrayList<>()).add(findingLine(f, l));
        }
        List<List<List<String>>> teethRows = new ArrayList<>();
        byTooth.forEach((fdi, lines) -> teethRows.add(List.of(List.of(fdi), lines)));
        model.put("teethGrid", grid(rtl, List.of(l.text("tooth"), l.text("state")), List.of("14mm", ""), teethRows));

        List<List<List<String>>> logRows = new ArrayList<>();
        for (PassportWork w : p.log()) {
            logRows.add(workCells(w, l, true, true));
        }
        model.put("logGrid", grid(rtl, List.of(l.text("date"), l.text("tooth"), l.text("work"), l.text("doneBy")),
                List.of("24mm", "18mm", "", ""), logRows));

        List<List<List<String>>> plannedRows = new ArrayList<>();
        for (PassportWork w : p.planned()) {
            List<List<String>> cells = workCells(w, l, false, false);
            plannedRows.add(List.of(cells.get(0), cells.get(1)));
        }
        model.put("plannedGrid", grid(rtl, List.of(l.text("tooth"), l.text("work")), List.of("18mm", ""), plannedRows));

        List<List<List<String>>> gumRows = new ArrayList<>();
        for (PassportGum g : p.gums()) {
            gumRows.add(List.of(
                    List.of(l.region(g.region())),
                    List.of(l.condition(g.condition()) + (g.stage() == null ? "" : " — " + l.text("stage") + " " + g.stage())
                            + (g.note() == null || g.note().isBlank() ? "" : " — " + g.note())),
                    List.of(g.assessedOn() == null ? "" : DAY.format(g.assessedOn()))));
        }
        model.put("gumsGrid", grid(rtl, List.of(l.text("area"), l.text("state"), l.text("date")), List.of("", "", "24mm"), gumRows));
        return model;
    }

    private String findingLine(PassportFinding f, TreatmentPassportLabels l) {
        StringBuilder line = new StringBuilder(l.finding(f.findingCode()));
        String where = TreatmentPassportLabels.shorthand(String.join("-", f.surfaces()));
        if (!where.isEmpty()) line.append(" (").append(where).append(')');
        String origin = origin(f.origin(), f.provider(), f.performedOn(), l);
        if (!origin.isEmpty()) line.append(" — ").append(origin);
        if (f.note() != null && !f.note().isBlank()) line.append(" — ").append(f.note());
        return line.toString();
    }

    /**
     * A table as the template draws it. The PDF renderer lays columns out left to
     * right whatever the document's direction, so for Arabic the columns are given
     * in reverse order here and the first column lands on the right, where an Arabic
     * reader starts.
     */
    private static Map<String, Object> grid(boolean rtl, List<String> headers, List<String> widths,
                                            List<List<List<String>>> rows) {
        List<String> h = new ArrayList<>(headers);
        List<String> w = new ArrayList<>(widths);
        List<List<List<String>>> r = new ArrayList<>();
        for (List<List<String>> row : rows) {
            r.add(new ArrayList<>(row));
        }
        if (rtl) {
            java.util.Collections.reverse(h);
            java.util.Collections.reverse(w);
            r.forEach(java.util.Collections::reverse);
        }
        Map<String, Object> grid = new LinkedHashMap<>();
        grid.put("headers", h);
        grid.put("widths", w);
        grid.put("rows", r);
        return grid;
    }

    /** Date, tooth, work and who did it, each as lines of text; {@code withDate} and {@code withWho} select the log's columns. */
    private List<List<String>> workCells(PassportWork w, TreatmentPassportLabels l, boolean withDate, boolean withWho) {
        String name = w.name() != null ? w.name() : l.finding(w.code());
        String where = TreatmentPassportLabels.shorthand(String.join("-", w.surfaces()));
        if (!where.isEmpty()) name += " (" + where + ")";
        if (w.actCode() != null && !w.actCode().isBlank()) name += " [" + w.actCode() + "]";
        if (withDate && "TREATED".equals(w.outcome())) name += " — " + l.text("treated");
        if (w.note() != null && !w.note().isBlank()) name += " — " + w.note();
        String date = w.date() == null ? (withDate ? l.text("dateUnknown") : "") : DAY.format(w.date());
        List<List<String>> cells = new ArrayList<>();
        if (withDate) cells.add(List.of(date));
        cells.add(List.of(String.join(", ", w.teeth())));
        cells.add(List.of(name));
        if (withWho) cells.add(List.of(origin(w.origin(), w.provider(), null, l)));
        return cells;
    }

    /** "Elsewhere, Dr Benani" — and the date when it is not already a column of its own. */
    private String origin(String origin, String provider, LocalDate date, TreatmentPassportLabels l) {
        List<String> parts = new ArrayList<>();
        if ("EXTERNAL".equals(origin)) parts.add(l.text("elsewhere"));
        if (provider != null && !provider.isBlank()) parts.add(provider);
        if (date != null) parts.add(DAY.format(date));
        return String.join(", ", parts);
    }

    private static List<String> surfaces(String surface) {
        return surface == null || surface.isBlank() ? List.of() : Arrays.asList(surface.split("-"));
    }

    private static String join(String separator, String first, String second) {
        return second == null || second.isBlank() ? first : first + separator + second;
    }
}
