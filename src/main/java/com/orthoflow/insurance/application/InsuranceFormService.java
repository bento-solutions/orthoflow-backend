package com.orthoflow.insurance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Beneficiary;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.CreateRequest;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.FormData;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Insured;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Issued;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.LayoutView;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Line;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.LineRequest;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Preview;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.SendRequest;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.View;
import com.orthoflow.insurance.application.port.SessionInsuranceForms;
import com.orthoflow.insurance.domain.model.InsuranceForm;
import com.orthoflow.insurance.domain.model.InsuranceForm.Purpose;
import com.orthoflow.insurance.domain.model.InsuranceForm.Source;
import com.orthoflow.insurance.domain.model.InsuranceForm.Status;
import com.orthoflow.insurance.infrastructure.forms.FormLayout;
import com.orthoflow.insurance.infrastructure.forms.FormLayouts;
import com.orthoflow.insurance.infrastructure.forms.FormValues;
import com.orthoflow.insurance.infrastructure.forms.PdfFormFiller;
import com.orthoflow.insurance.infrastructure.persistence.InsuranceFormJpaRepository;
import com.orthoflow.patient.application.port.InsurerRef;
import com.orthoflow.patient.application.port.PatientIdentity;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.tasks.application.dto.TaskDtos;
import com.orthoflow.tasks.application.service.TaskService;
import com.orthoflow.tasks.domain.model.Task;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import com.orthoflow.treatment.application.port.TreatmentActLookup;
import com.orthoflow.treatment.application.port.TreatmentActLookup.CodedTreatment;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The patient's own insurer's care form, filled and handed to the front desk.
 *
 * <p>Which form: the clinic's choice for the insurer, else the form OrthoFlow knows for
 * the insurer's code (CNOPS for the public-sector mutuals, CNSS...), else OrthoFlow's
 * statement of acts, which says plainly that it is not the insurer's sheet. Nothing is
 * guessed: a field the record lacks is left blank on the form and named in
 * {@code missing}, so the front desk completes it rather than discovering it at the insurer.
 *
 * <p>Each form is rendered once and kept with a snapshot of what it says. Refreshing it
 * from the patient's record is a deliberate step, and only before it is printed.
 */
@Service
@RequiredArgsConstructor
public class InsuranceFormService implements SessionInsuranceForms {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String SERIES = "insurance_form";

    private final InsuranceFormJpaRepository forms;
    private final PatientLookup patients;
    private final PractitionerService practitioners;
    private final TreatmentActLookup acts;
    private final FormLayouts layouts;
    private final PdfFormFiller filler;
    private final PdfService pdfService;
    private final LetterheadProvider letterheads;
    private final FileService files;
    private final DocumentNumbers numbers;
    private final TaskService tasks;
    private final PracticeZone zone;
    private final ObjectMapper json;

    // ── Reading ─────────────────────────────────────────────────────────

    public List<LayoutView> layouts() {
        return layouts.all().stream().map(l -> new LayoutView(l.code(), l.name(), l.insurerCodes(), l.official(),
                l.singleUse(), l.source(), l.note())).toList();
    }

    /** Which form a patient's paperwork would use today, and what their record still lacks for it. */
    @Transactional(readOnly = true)
    public Preview preview(UUID practiceId, UUID actorId, UUID patientId, UUID practitionerId) {
        PatientIdentity p = patients.findIdentity(patientId).orElseThrow(() -> new NotFoundException("Patient not found"));
        InsurerRef insurer = patients.findInsurerOf(p.id()).orElse(null);
        Optional<FormLayout> layout = layoutFor(insurer);
        FormData d = data(practiceId, p, insurer, layout, Purpose.EXECUTION, today(practiceId),
                practitioner(practiceId, practitionerId, actorId, patientId), List.of(), null);
        return new Preview(insurer == null ? null : insurer.id(), d.insurerName(), d.formCode(), d.formName(),
                layout.map(FormLayout::official).orElse(false), layout.map(FormLayout::singleUse).orElse(false),
                missing(d, layout));
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID practiceId, UUID patientId, Status status) {
        List<InsuranceForm> rows = forms.search(practiceId, patientId, status, PageRequest.of(0, 200));
        Map<UUID, String> names = new HashMap<>();
        return rows.stream().map(f -> view(f, names.computeIfAbsent(f.getPatientId(), this::patientName))).toList();
    }

    @Transactional(readOnly = true)
    public View get(UUID practiceId, UUID id) {
        InsuranceForm f = require(practiceId, id);
        return view(f, patientName(f.getPatientId()));
    }

    /**
     * The form as issued, or with {@code overlay} only what OrthoFlow wrote, on blank
     * pages of the same size: to print onto the patient's own numbered paper form.
     */
    @Transactional(readOnly = true)
    public byte[] file(UUID practiceId, UUID id, boolean overlay) {
        InsuranceForm f = require(practiceId, id);
        if (!overlay) {
            if (f.getFileId() == null) {
                throw new NotFoundException("This form has no file");
            }
            return files.read(files.require(practiceId, f.getFileId()));
        }
        FormLayout layout = layouts.find(f.getFormCode())
                .orElseThrow(() -> new ValidationException("This form is OrthoFlow's own statement; there is no insurer sheet to print onto"));
        return filler.fill(layout, layouts.template(layout), FormValuesMapper.map(snapshot(f)), true);
    }

    // ── Issuing ─────────────────────────────────────────────────────────

    /** A form made at the front desk or by the doctor outside a consultation. */
    @Transactional
    public View create(UUID practiceId, UUID actorId, CreateRequest r) {
        PatientIdentity p = patients.findIdentity(r.patientId()).orElseThrow(() -> new NotFoundException("Patient not found"));
        LocalDate careDate = r.careDate() != null ? r.careDate() : today(practiceId);
        List<Line> lines = lines(r.lines(), careDate, r.purpose());
        Practitioner practitioner = practitioner(practiceId, r.practitionerId(), actorId, p.id());
        InsuranceForm f = issue(practiceId, actorId, p, r.purpose(), careDate, practitioner, lines, Source.MANUAL, null,
                blankToNull(r.agreementNumber()), blankToNull(r.notes()));
        if (r.send()) {
            sendTask(practiceId, actorId, f, r.assigneeId(), null);
        }
        return view(f, p.fullName());
    }

    @Override
    @Transactional
    public List<Issued> afterSession(UUID practiceId, UUID actorId, UUID consultationId, UUID patientId, List<SessionAct> plan) {
        PatientIdentity p = patients.findIdentity(patientId).orElseThrow(() -> new NotFoundException("Patient not found"));
        if (patients.findInsurerOf(patientId).isEmpty() && blankToNull(p.insuranceProvider()) == null) {
            return List.of();
        }
        Map<UUID, CodedTreatment> coded = acts.byTreatmentIds(plan.stream().map(SessionAct::treatmentId).filter(Objects::nonNull).toList());
        LocalDate today = today(practiceId);
        List<Line> done = new ArrayList<>();
        List<Line> proposed = new ArrayList<>();
        for (SessionAct a : plan) {
            CodedTreatment t = a.treatmentId() == null ? null : coded.get(a.treatmentId());
            String code = t == null ? null : blankToNull(t.actCode());
            String label = blankToNull(a.label()) != null ? a.label().trim() : t == null ? null : t.name();
            if (a.performed()) {
                done.add(new Line(today, a.teeth(), code, label, t == null ? null : t.cotation(), a.amount()));
            } else if (t != null && CareType.needsPriorAgreement(code, t.priorAgreement())) {
                proposed.add(new Line(null, a.teeth(), code, label, t.cotation(), a.amount()));
            }
        }
        Practitioner practitioner = practitioner(practiceId, null, actorId, patientId);
        List<Issued> issued = new ArrayList<>();
        if (!done.isEmpty()) {
            issued.add(issueAndSend(practiceId, actorId, p, Purpose.EXECUTION, today, practitioner, done, consultationId));
        }
        if (!proposed.isEmpty()) {
            issued.add(issueAndSend(practiceId, actorId, p, Purpose.PRIOR_AGREEMENT, today, practitioner, proposed, consultationId));
        }
        return issued;
    }

    private Issued issueAndSend(UUID practiceId, UUID actorId, PatientIdentity p, Purpose purpose, LocalDate day,
                                Practitioner practitioner, List<Line> lines, UUID consultationId) {
        InsuranceForm f = issue(practiceId, actorId, p, purpose, day, practitioner, lines, Source.CONSULTATION, consultationId, null, null);
        sendTask(practiceId, actorId, f, null, null);
        FormData d = snapshot(f);
        return new Issued(f.getId(), f.getNumber(), d.insurerName(), d.formName(), purpose, lines.size(), f.getTotal(), true);
    }

    private InsuranceForm issue(UUID practiceId, UUID actorId, PatientIdentity p, Purpose purpose, LocalDate careDate,
                                Practitioner practitioner, List<Line> lines, Source source, UUID consultationId,
                                String agreementNumber, String notes) {
        InsurerRef insurer = patients.findInsurerOf(p.id()).orElse(null);
        Optional<FormLayout> layout = layoutFor(insurer);
        FormData d = data(practiceId, p, insurer, layout, purpose, careDate, practitioner, lines, agreementNumber);
        String number = "FSA-" + careDate.getYear() + "-" + String.format("%05d", numbers.next(practiceId, SERIES));
        InsuranceForm f = InsuranceForm.builder().id(UUID.randomUUID()).practiceId(practiceId).number(number)
                .patientId(p.id()).insurerId(insurer == null ? null : insurer.id()).formCode(d.formCode())
                .purpose(purpose).source(source).consultationId(consultationId)
                .practitionerId(practitioner == null ? null : practitioner.getId()).careDate(careDate)
                .lines(write(lines)).total(d.total()).snapshot(write(d)).notes(notes).createdBy(actorId).build();
        f.setFileId(store(practiceId, actorId, f, d, layout));
        return forms.save(f);
    }

    // ── The front desk's part ───────────────────────────────────────────

    /** Hands the form to the front desk: a task for the named person, else for every assistant. */
    @Transactional
    public View send(UUID practiceId, UUID actorId, UUID id, SendRequest r) {
        InsuranceForm f = require(practiceId, id);
        if (f.getStatus() == Status.VOID) {
            throw new ConflictException("A voided form cannot be sent");
        }
        sendTask(practiceId, actorId, f, r == null ? null : r.assigneeId(), r == null ? null : blankToNull(r.message()));
        return view(forms.save(f), patientName(f.getPatientId()));
    }

    @Transactional
    public View markPrinted(UUID practiceId, UUID id) {
        InsuranceForm f = require(practiceId, id);
        if (f.getStatus() == Status.VOID || f.getStatus() == Status.HANDED_OVER) {
            throw new ConflictException("This form is " + f.getStatus());
        }
        f.setStatus(Status.PRINTED);
        if (f.getPrintedAt() == null) f.setPrintedAt(OffsetDateTime.now());
        return view(f, patientName(f.getPatientId()));
    }

    @Transactional
    public View markHandedOver(UUID practiceId, UUID id) {
        InsuranceForm f = require(practiceId, id);
        if (f.getStatus() == Status.VOID) {
            throw new ConflictException("A voided form cannot be handed over");
        }
        if (f.getPrintedAt() == null) f.setPrintedAt(OffsetDateTime.now());
        f.setStatus(Status.HANDED_OVER);
        f.setHandedOverAt(OffsetDateTime.now());
        return view(f, patientName(f.getPatientId()));
    }

    @Transactional
    public View voidForm(UUID practiceId, UUID id) {
        InsuranceForm f = require(practiceId, id);
        f.setStatus(Status.VOID);
        return view(f, patientName(f.getPatientId()));
    }

    /**
     * Re-reads the patient's record (an immatriculation typed in since, a new insurer) and
     * renders the form again with the same acts. Only before it is printed: once on paper,
     * the kept copy must stay what was printed.
     */
    @Transactional
    public View refresh(UUID practiceId, UUID actorId, UUID id) {
        InsuranceForm f = require(practiceId, id);
        if (f.getStatus() != Status.TO_PRINT) {
            throw new ConflictException("Only a form not yet printed can be refreshed; void it and make a new one");
        }
        FormData old = snapshot(f);
        PatientIdentity p = patients.findIdentity(f.getPatientId()).orElseThrow(() -> new NotFoundException("Patient not found"));
        InsurerRef insurer = patients.findInsurerOf(p.id()).orElse(null);
        Optional<FormLayout> layout = layoutFor(insurer);
        Practitioner practitioner = f.getPractitionerId() == null ? null : practitioners.require(practiceId, f.getPractitionerId());
        FormData d = data(practiceId, p, insurer, layout, f.getPurpose(), f.getCareDate(), practitioner, old.lines(), old.agreementNumber());
        f.setInsurerId(insurer == null ? null : insurer.id());
        f.setFormCode(d.formCode());
        f.setSnapshot(write(d));
        f.setFileId(store(practiceId, actorId, f, d, layout));
        return view(forms.save(f), p.fullName());
    }

    private void sendTask(UUID practiceId, UUID actorId, InsuranceForm f, UUID assigneeId, String message) {
        FormData d = snapshot(f);
        Optional<FormLayout> layout = layouts.find(f.getFormCode());
        String who = d.beneficiary() == null ? "" : " — " + d.beneficiary().fullName();
        String title = (f.getPurpose() == Purpose.PRIOR_AGREEMENT ? "Entente préalable " : "Feuille de soins ")
                + (d.insurerName() == null ? "" : d.insurerName()) + who;
        StringBuilder body = new StringBuilder();
        body.append(f.getPurpose() == Purpose.PRIOR_AGREEMENT ? "Demande d'entente préalable" : "Exécution")
                .append(" · ").append(f.getNumber()).append(" · ").append(d.lines().size()).append(" acte(s)");
        if (f.getTotal() != null && f.getTotal().signum() > 0) {
            body.append(", ").append(FormValuesMapper.money(f.getTotal())).append(" DH");
        }
        body.append(".\n");
        if (layout.isPresent()) {
            body.append("Imprimer « ").append(layout.get().name()).append(" »");
        } else {
            body.append("Imprimer le relevé des actes (formulaire de l'assureur non disponible dans OrthoFlow : le joindre à la feuille du patient)");
        }
        body.append(d.practitionerName() == null ? ", le faire signer et cacheter" : ", le faire signer et cacheter par " + d.practitionerName())
                .append(", puis le remettre au patient.");
        if (layout.map(FormLayout::singleUse).orElse(false)) {
            body.append("\nFormulaire numéroté : imprimer la version « calque » sur la feuille papier du patient.");
        }
        List<String> missing = missing(d, layout);
        if (!missing.isEmpty()) {
            body.append("\nÀ compléter : ").append(String.join(", ", missing.stream().map(InsuranceFormService::missingLabel).toList())).append('.');
        }
        if (message != null) {
            body.append("\n").append(message);
        }
        TaskDtos.Request request = new TaskDtos.Request(title.length() > 300 ? title.substring(0, 300) : title, body.toString(),
                assigneeId, assigneeId == null ? UserRole.ASSISTANT : null, today(practiceId), Task.Priority.NORMAL, f.getPatientId());
        f.setTaskId(tasks.createForDocument(practiceId, actorId, request, Task.DocumentKind.INSURANCE_FORM, f.getId()).id());
    }

    // ── What a form says ────────────────────────────────────────────────

    private FormData data(UUID practiceId, PatientIdentity p, InsurerRef insurer, Optional<FormLayout> layout, Purpose purpose,
                          LocalDate careDate, Practitioner practitioner, List<Line> lines, String agreementNumber) {
        String relation = p.insuredRelation() == null ? "SELF" : p.insuredRelation();
        String patientName = FormValuesMapper.formName(p.firstName(), p.lastName());
        boolean self = "SELF".equals(relation);
        String insuredName = self ? patientName
                : Optional.ofNullable(blankToNull(p.insuredName())).orElse(blankToNull(p.guardianName()));
        Insured insured = new Insured(insuredName, self ? blankToNull(p.cin()) : blankToNull(p.insuredCin()),
                blankToNull(p.insuranceNumber()), blankToNull(p.insuranceAffiliationNumber()), blankToNull(p.address()),
                blankToNull(p.phone()));
        Beneficiary beneficiary = new Beneficiary(patientName, blankToNull(p.cin()), p.dateOfBirth(), blankToNull(p.gender()));
        Letterhead lh = letterheads.forPractice(practiceId);
        String insurerName = insurer != null ? insurer.name() : blankToNull(p.insuranceProvider());
        BigDecimal total = lines.stream().map(Line::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FormData(insurerName, insurer == null ? null : insurer.code(),
                layout.map(FormLayout::code).orElse(FormLayouts.GENERIC),
                layout.map(FormLayout::name).orElse("Relevé des actes dentaires (OrthoFlow)"), purpose, careDate, insured,
                beneficiary, relation, practitioner == null ? null : practitioner.getDisplayName(),
                practitioner == null ? null : blankToNull(practitioner.getInpe()), blankToNull(lh.name()),
                blankToNull(lh.address()), blankToNull(lh.city()), blankToNull(lh.phone()), agreementNumber, lines, total);
    }

    /** What the record lacks for this form, as codes the screens translate. */
    static List<String> missing(FormData d, Optional<FormLayout> layout) {
        List<String> out = new ArrayList<>();
        if (d.insurerName() == null) out.add("INSURER");
        if (d.insured().immatriculation() == null) out.add("INSURANCE_NUMBER");
        if (uses(layout, "insured.affiliation") && d.insured().affiliation() == null) out.add("AFFILIATION_NUMBER");
        if (!"SELF".equals(d.relation()) && d.insured().fullName() == null) out.add("INSURED_NAME");
        if (uses(layout, "insured.cin") && d.insured().cin() == null) out.add("INSURED_CIN");
        if (layout.isPresent() && d.beneficiary().dateOfBirth() == null) out.add("DATE_OF_BIRTH");
        if (layout.isPresent() && !"M".equalsIgnoreCase(d.beneficiary().sex()) && !"F".equalsIgnoreCase(d.beneficiary().sex())) {
            out.add("GENDER");
        }
        if (d.practitionerInpe() == null) out.add("PRACTITIONER_INPE");
        if (d.lines().stream().anyMatch(l -> l.code() == null)) out.add("ACT_CODES");
        return out;
    }

    private static boolean uses(Optional<FormLayout> layout, String key) {
        return layout.map(l -> (l.text() != null && l.text().containsKey(key)) || (l.cells() != null && l.cells().containsKey(key)))
                .orElse(false);
    }

    static String missingLabel(String code) {
        return switch (code) {
            case "INSURER" -> "organisme d'assurance";
            case "INSURANCE_NUMBER" -> "n° d'immatriculation";
            case "AFFILIATION_NUMBER" -> "n° d'affiliation";
            case "INSURED_NAME" -> "nom de l'assuré(e)";
            case "INSURED_CIN" -> "CIN de l'assuré(e)";
            case "DATE_OF_BIRTH" -> "date de naissance";
            case "GENDER" -> "sexe";
            case "PRACTITIONER_INPE" -> "INPE du praticien";
            case "ACT_CODES" -> "code NGAP des actes";
            default -> code;
        };
    }

    private UUID store(UUID practiceId, UUID actorId, InsuranceForm f, FormData d, Optional<FormLayout> layout) {
        byte[] pdf = layout.isPresent()
                ? filler.fill(layout.get(), layouts.template(layout.get()), FormValuesMapper.map(d), false)
                : statement(practiceId, f, d);
        return files.storeGenerated(practiceId, FileOwnerType.INSURANCE_FORM, f.getId(), f.getNumber() + ".pdf",
                "application/pdf", pdf, actorId).getId();
    }

    private byte[] statement(UUID practiceId, InsuranceForm f, FormData d) {
        FormValues values = FormValuesMapper.map(d);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < d.lines().size(); i++) {
            Line l = d.lines().get(i);
            Map<String, String> row = new LinkedHashMap<>(values.rows().get(i));
            row.put("date", l.date() == null ? "" : DAY.format(l.date()));
            for (String k : List.of("teeth", "code", "label", "cotation", "amount")) row.putIfAbsent(k, "");
            rows.add(row);
        }
        Letterhead lh = letterheads.forPractice(practiceId);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", lh);
        model.put("data", d);
        model.put("rows", rows);
        model.put("total", FormValuesMapper.money(d.total()));
        model.put("form", Map.of("number", f.getNumber(), "date", DAY.format(d.careDate())));
        model.put("priorAgreement", d.purpose() == Purpose.PRIOR_AGREEMENT);
        model.put("relation", switch (d.relation()) {
            case "SPOUSE" -> "conjoint(e)";
            case "CHILD" -> "enfant";
            default -> "lui-même / elle-même";
        });
        model.put("birthDate", d.beneficiary().dateOfBirth() == null ? null : DAY.format(d.beneficiary().dateOfBirth()));
        model.put("signedAt", "Fait à " + (lh.city() == null || lh.city().isBlank() ? "…" : lh.city()) + ", le " + DAY.format(d.careDate()));
        return pdfService.render("insurance-statement", model, "fr");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Optional<FormLayout> layoutFor(InsurerRef insurer) {
        return insurer == null ? Optional.empty() : layouts.forInsurer(insurer.code(), insurer.formCode());
    }

    /** Lines typed or picked: a catalogue treatment brings its insurer code and cotation unless the line gives its own. */
    private List<Line> lines(List<LineRequest> requested, LocalDate careDate, Purpose purpose) {
        Map<UUID, CodedTreatment> coded = acts.byTreatmentIds(requested.stream().map(LineRequest::treatmentId).filter(Objects::nonNull).toList());
        List<Line> out = new ArrayList<>();
        for (LineRequest r : requested) {
            CodedTreatment t = r.treatmentId() == null ? null : coded.get(r.treatmentId());
            if (r.treatmentId() != null && t == null) {
                throw new NotFoundException("Treatment not found");
            }
            String code = blankToNull(r.code()) != null ? r.code().trim() : t == null ? null : blankToNull(t.actCode());
            String cotation = blankToNull(r.cotation()) != null ? r.cotation().trim() : t == null ? null : t.cotation();
            String label = blankToNull(r.label()) != null ? r.label().trim() : t == null ? null : t.name();
            if (code == null && label == null) {
                throw new ValidationException("Each act needs a code, a label or a treatment");
            }
            LocalDate date = r.date() != null ? r.date() : purpose == Purpose.EXECUTION ? careDate : null;
            out.add(new Line(date, blankToNull(r.teeth()), code, label, cotation, r.amount()));
        }
        return out;
    }

    /** The named practitioner, else the signed-in user's own, else the patient's usual one. */
    private Practitioner practitioner(UUID practiceId, UUID requested, UUID actorId, UUID patientId) {
        if (requested != null) {
            return practitioners.require(practiceId, requested);
        }
        Optional<UUID> id = actorId == null ? Optional.empty() : practitioners.findIdByUser(actorId);
        if (id.isEmpty()) {
            id = patients.findPrimaryPractitionerId(patientId);
        }
        return id.map(v -> practitioners.require(practiceId, v)).orElse(null);
    }

    private View view(InsuranceForm f, String patientName) {
        FormData d = snapshot(f);
        Optional<FormLayout> layout = layouts.find(f.getFormCode());
        return new View(f.getId(), f.getNumber(), f.getPatientId(), patientName, f.getInsurerId(), d.insurerName(),
                f.getFormCode(), d.formName(), layout.map(FormLayout::official).orElse(false),
                layout.map(FormLayout::singleUse).orElse(false), f.getPurpose(), f.getSource(), f.getConsultationId(),
                f.getCareDate(), d.lines(), f.getTotal(), f.getStatus(), f.getTaskId(), f.getPrintedAt(), f.getHandedOverAt(),
                f.getCreatedAt(), f.getNotes(), missing(d, layout));
    }

    private FormData snapshot(InsuranceForm f) {
        try {
            return json.readValue(f.getSnapshot(), FormData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable snapshot on insurance form " + f.getId(), e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise an insurance form", e);
        }
    }

    private InsuranceForm require(UUID practiceId, UUID id) {
        return forms.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Insurance form not found"));
    }

    private String patientName(UUID patientId) {
        return patients.findSummary(patientId).map(PatientSummary::fullName).orElse(null);
    }

    private LocalDate today(UUID practiceId) {
        return LocalDate.now(zone.of(practiceId));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
