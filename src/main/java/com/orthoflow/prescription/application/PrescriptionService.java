package com.orthoflow.prescription.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.clinical.application.dto.AllergyResponse;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.patient.application.port.PatientIdentity;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.AllergyWarning;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.CheckRequest;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.IssueRequest;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.LibraryEntry;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.Line;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.TemplateRequest;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.TemplateView;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.View;
import com.orthoflow.prescription.domain.model.Prescription;
import com.orthoflow.prescription.domain.model.PrescriptionTemplate;
import com.orthoflow.prescription.infrastructure.PrescriptionJpaRepository;
import com.orthoflow.prescription.infrastructure.PrescriptionTemplateJpaRepository;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ordonnances: the clinic's templates, the reference library they can be adopted from,
 * the allergy check, and the numbered PDF the doctor signs.
 *
 * <p>The library is educational content from ordonnance.ma. It is never printed straight
 * from the library: a clinic adopts an entry, which stays flagged "to review" until one of
 * its doctors validates it, and every prescription is the doctor's own lines, whatever
 * template they started from.
 */
@Service
@RequiredArgsConstructor
public class PrescriptionService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final TypeReference<List<Line>> LINES = new TypeReference<>() {
    };
    private static final TypeReference<List<AllergyWarning>> WARNINGS = new TypeReference<>() {
    };

    private final PrescriptionJpaRepository prescriptions;
    private final PrescriptionTemplateJpaRepository templates;
    private final PrescriptionLibrary library;
    private final ClinicalRecordService clinical;
    private final PatientLookup patients;
    private final PractitionerService practitioners;
    private final PdfService pdfService;
    private final LetterheadProvider letterheads;
    private final FileService files;
    private final DocumentNumbers numbers;
    private final JdbcTemplate jdbc;
    private final PracticeZone zone;
    private final ObjectMapper json;

    // ── Library and templates ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LibraryEntry> library(UUID practiceId) {
        Set<String> adopted = templates.findByPracticeIdOrderByCategoryAscNameAsc(practiceId).stream()
                .map(PrescriptionTemplate::getLibraryCode).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        return library.all().stream().map(e -> new LibraryEntry(e.code(), e.name(), e.category(), e.lines(), e.advice(),
                e.warningSigns(), e.alternative(), e.sourceUrl(), adopted.contains(e.code()))).toList();
    }

    @Transactional(readOnly = true)
    public List<TemplateView> templates(UUID practiceId, boolean includeInactive) {
        List<PrescriptionTemplate> rows = templates.findByPracticeIdOrderByCategoryAscNameAsc(practiceId);
        Map<UUID, String> names = userNames(practiceId);
        return rows.stream().filter(t -> includeInactive || t.isActive()).map(t -> templateView(t, names)).toList();
    }

    /**
     * Copies a library entry into the clinic's templates, unreviewed. Its advice keeps
     * what the patient should read (care advice, the signs that call for urgent help);
     * the alternative for an allergic patient stays in the library, for the doctor.
     */
    @Transactional
    public TemplateView adopt(UUID practiceId, UUID actorId, String code) {
        PrescriptionLibrary.Entry e = library.find(code).orElseThrow(() -> new NotFoundException("Library entry not found"));
        if (templates.existsByPracticeIdAndLibraryCode(practiceId, code)) {
            throw new ConflictException("This library entry is already one of the clinic's templates");
        }
        String name = templates.existsByPracticeIdAndNameIgnoreCase(practiceId, e.name()) ? e.name() + " (bibliothèque)" : e.name();
        StringBuilder advice = new StringBuilder();
        if (e.advice() != null) advice.append(e.advice());
        if (e.warningSigns() != null) {
            if (advice.length() > 0) advice.append("\n");
            advice.append("Consulter en urgence en cas de : ").append(lowerFirst(e.warningSigns()));
        }
        PrescriptionTemplate t = templates.save(PrescriptionTemplate.builder().practiceId(practiceId).name(name)
                .category(PrescriptionTemplate.Category.valueOf(e.category())).lines(write(e.lines()))
                .advice(advice.length() == 0 ? null : advice.toString()).libraryCode(code).createdBy(actorId).build());
        return templateView(t, userNames(practiceId));
    }

    /** A template a clinician writes is theirs, so it counts as reviewed by them. */
    @Transactional
    public TemplateView createTemplate(UUID practiceId, UUID actorId, TemplateRequest r) {
        if (templates.existsByPracticeIdAndNameIgnoreCase(practiceId, r.name().trim())) {
            throw new ConflictException("A template named " + r.name().trim() + " already exists");
        }
        PrescriptionTemplate t = templates.save(PrescriptionTemplate.builder().practiceId(practiceId).name(r.name().trim())
                .category(r.category() == null ? PrescriptionTemplate.Category.OTHER : r.category()).lines(write(clean(r.lines())))
                .advice(blankToNull(r.advice())).active(r.active() == null || r.active()).reviewedBy(actorId)
                .reviewedAt(OffsetDateTime.now()).createdBy(actorId).build());
        return templateView(t, userNames(practiceId));
    }

    /** Editing is reading: whoever saves a template's lines has reviewed them. */
    @Transactional
    public TemplateView updateTemplate(UUID practiceId, UUID actorId, UUID id, TemplateRequest r) {
        PrescriptionTemplate t = requireTemplate(practiceId, id);
        String name = r.name().trim();
        if (!name.equalsIgnoreCase(t.getName()) && templates.existsByPracticeIdAndNameIgnoreCase(practiceId, name)) {
            throw new ConflictException("A template named " + name + " already exists");
        }
        t.setName(name);
        if (r.category() != null) t.setCategory(r.category());
        t.setLines(write(clean(r.lines())));
        t.setAdvice(blankToNull(r.advice()));
        if (r.active() != null) t.setActive(r.active());
        t.setReviewedBy(actorId);
        t.setReviewedAt(OffsetDateTime.now());
        return templateView(templates.save(t), userNames(practiceId));
    }

    @Transactional
    public TemplateView review(UUID practiceId, UUID actorId, UUID id) {
        PrescriptionTemplate t = requireTemplate(practiceId, id);
        t.setReviewedBy(actorId);
        t.setReviewedAt(OffsetDateTime.now());
        return templateView(templates.save(t), userNames(practiceId));
    }

    /** Prescriptions issued from it keep their own lines; they only lose the link. */
    @Transactional
    public void deleteTemplate(UUID practiceId, UUID id) {
        templates.delete(requireTemplate(practiceId, id));
    }

    // ── Prescriptions ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AllergyWarning> check(UUID practiceId, CheckRequest r) {
        requirePatient(r.patientId());
        return AllergyCheck.check(clean(r.lines()), allergies(r.patientId()));
    }

    @Transactional
    public View issue(UUID practiceId, UUID actorId, IssueRequest r) {
        PatientIdentity p = requirePatient(r.patientId());
        List<Line> lines = clean(r.lines());
        List<AllergyWarning> warnings = AllergyCheck.check(lines, allergies(p.id()));
        if (!warnings.isEmpty() && !r.acknowledgeWarnings()) {
            throw new ValidationException("A drug on this prescription is related to the patient's allergies ("
                    + warnings.stream().map(w -> w.drug() + " / " + w.allergy()).collect(Collectors.joining(", "))
                    + "); confirm to issue it");
        }
        if (r.templateId() != null) {
            requireTemplate(practiceId, r.templateId());
        }
        Practitioner practitioner = practitioner(practiceId, r.practitionerId(), actorId, p.id());
        LocalDate today = LocalDate.now(zone.of(practiceId));
        String number = "ORD-" + today.getYear() + "-" + String.format("%05d", numbers.next(practiceId, "prescription"));
        Prescription rx = Prescription.builder().id(UUID.randomUUID()).practiceId(practiceId).number(number).patientId(p.id())
                .practitionerId(practitioner == null ? null : practitioner.getId()).consultationId(r.consultationId())
                .templateId(r.templateId()).lines(write(lines)).advice(blankToNull(r.advice()))
                .allergyWarnings(warnings.isEmpty() ? null : write(warnings)).createdBy(actorId).build();
        byte[] pdf = render(practiceId, rx, p, practitioner, lines, today);
        rx.setFileId(files.storeGenerated(practiceId, FileOwnerType.PRESCRIPTION, rx.getId(), number + ".pdf",
                "application/pdf", pdf, actorId).getId());
        return view(prescriptions.save(rx), p.fullName(), practitioner);
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID practiceId, UUID patientId) {
        List<Prescription> rows = prescriptions.findTop100ByPracticeIdAndPatientIdOrderByIssuedAtDesc(practiceId, patientId);
        String name = patients.findSummary(patientId).map(PatientSummary::fullName).orElse(null);
        Map<UUID, Practitioner> doctors = practitioners.byIds(rows.stream().map(Prescription::getPractitionerId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(rx -> view(rx, name, doctors.get(rx.getPractitionerId()))).toList();
    }

    @Transactional(readOnly = true)
    public byte[] file(UUID practiceId, UUID id) {
        Prescription rx = require(practiceId, id);
        if (rx.getFileId() == null) {
            throw new NotFoundException("This prescription has no file");
        }
        return files.read(files.require(practiceId, rx.getFileId()));
    }

    @Transactional
    public View voidPrescription(UUID practiceId, UUID id) {
        Prescription rx = require(practiceId, id);
        rx.setStatus(Prescription.Status.VOID);
        Practitioner doctor = rx.getPractitionerId() == null ? null : practitioners.require(practiceId, rx.getPractitionerId());
        return view(rx, patients.findSummary(rx.getPatientId()).map(PatientSummary::fullName).orElse(null), doctor);
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private byte[] render(UUID practiceId, Prescription rx, PatientIdentity p, Practitioner practitioner, List<Line> lines,
                          LocalDate today) {
        Letterhead lh = letterheads.forPractice(practiceId);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", lh);
        model.put("number", rx.getNumber());
        model.put("signedAt", (lh.city() == null || lh.city().isBlank() ? "" : lh.city() + ", le ") + DAY.format(today));
        model.put("practitioner", practitioner == null ? null : Map.of("name", practitioner.getDisplayName(),
                "inpe", practitioner.getInpe() == null ? "" : practitioner.getInpe()));
        Map<String, Object> patient = new HashMap<>();
        patient.put("name", p.lastName().toUpperCase(Locale.FRENCH) + " " + p.firstName());
        patient.put("age", age(p.dateOfBirth(), today));
        model.put("patient", patient);
        model.put("lines", lines);
        model.put("advice", rx.getAdvice() == null ? List.of() : List.of(rx.getAdvice().split("\\r?\\n")));
        return pdfService.render("prescription", model, "fr");
    }

    /** "12 ans", "8 mois": what an ordonnance writes for a child's dose to be read against. */
    static String age(LocalDate dob, LocalDate today) {
        if (dob == null || dob.isAfter(today)) return null;
        Period age = Period.between(dob, today);
        if (age.getYears() >= 2) return age.getYears() + " ans";
        int months = age.getYears() * 12 + age.getMonths();
        return months + " mois";
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private List<String> allergies(UUID patientId) {
        return clinical.listAllergies(patientId).stream().map(AllergyResponse::substance).toList();
    }

    private Practitioner practitioner(UUID practiceId, UUID requested, UUID actorId, UUID patientId) {
        if (requested != null) {
            return practitioners.require(practiceId, requested);
        }
        Optional<UUID> id = practitioners.findIdByUser(actorId);
        if (id.isEmpty()) {
            id = patients.findPrimaryPractitionerId(patientId);
        }
        return id.map(v -> practitioners.require(practiceId, v)).orElse(null);
    }

    private static List<Line> clean(List<Line> lines) {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            out.add(new Line(l.drug().trim(), blankToNull(l.form()), blankToNull(l.dci()), l.posology().trim()));
        }
        return out;
    }

    private View view(Prescription rx, String patientName, Practitioner practitioner) {
        return new View(rx.getId(), rx.getNumber(), rx.getPatientId(), patientName, rx.getPractitionerId(),
                practitioner == null ? null : practitioner.getDisplayName(), rx.getConsultationId(), rx.getTemplateId(),
                read(rx.getLines(), LINES), rx.getAdvice(),
                rx.getAllergyWarnings() == null ? List.of() : read(rx.getAllergyWarnings(), WARNINGS), rx.getStatus(),
                rx.getIssuedAt());
    }

    private TemplateView templateView(PrescriptionTemplate t, Map<UUID, String> names) {
        return new TemplateView(t.getId(), t.getName(), t.getCategory(), read(t.getLines(), LINES), t.getAdvice(),
                t.getLibraryCode(), t.getReviewedAt() != null, t.getReviewedAt(),
                t.getReviewedBy() == null ? null : names.get(t.getReviewedBy()), t.isActive());
    }

    private Map<UUID, String> userNames(UUID practiceId) {
        Map<UUID, String> names = new HashMap<>();
        jdbc.query("SELECT id, first_name || ' ' || last_name AS name FROM users WHERE practice_id = ?",
                rs -> {
                    names.put(rs.getObject("id", UUID.class), rs.getString("name"));
                }, practiceId);
        return names;
    }

    private PatientIdentity requirePatient(UUID patientId) {
        return patients.findIdentity(patientId).orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    private Prescription require(UUID practiceId, UUID id) {
        return prescriptions.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Prescription not found"));
    }

    private PrescriptionTemplate requireTemplate(UUID practiceId, UUID id) {
        return templates.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Template not found"));
    }

    private <T> T read(String raw, TypeReference<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable prescription data", e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a prescription", e);
        }
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
