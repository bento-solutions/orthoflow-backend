package com.orthoflow.insurance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.CreateRequest;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos.LineRequest;
import com.orthoflow.insurance.application.port.SessionInsuranceForms.SessionAct;
import com.orthoflow.insurance.domain.model.InsuranceForm;
import com.orthoflow.insurance.infrastructure.forms.FormLayouts;
import com.orthoflow.insurance.infrastructure.forms.PdfFormFiller;
import com.orthoflow.insurance.infrastructure.persistence.InsuranceFormJpaRepository;
import com.orthoflow.patient.application.port.InsurerRef;
import com.orthoflow.patient.application.port.PatientIdentity;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import com.orthoflow.tasks.application.dto.TaskDtos;
import com.orthoflow.tasks.application.service.TaskService;
import com.orthoflow.tasks.domain.model.Task;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import com.orthoflow.treatment.application.port.TreatmentActLookup;
import com.orthoflow.treatment.application.port.TreatmentActLookup.CodedTreatment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What ends up on the patient's insurer form, and who gets it. The real layouts and filler
 * draw the official forms, so a CNOPS patient's form is a filled CNOPS sheet here too.
 */
class InsuranceFormServiceTest {

    private static final UUID PRACTICE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID CNOPS = UUID.randomUUID();
    private static final UUID AXA = UUID.randomUUID();
    private static final UUID CONSULTATION = UUID.randomUUID();
    private static final UUID CONSULT_ACT = UUID.randomUUID();
    private static final UUID ODF_ACT = UUID.randomUUID();
    private static final UUID FILLING = UUID.randomUUID();

    private final List<InsuranceForm> saved = new ArrayList<>();
    private InsuranceFormJpaRepository forms;
    private PatientLookup patients;
    private PractitionerService practitioners;
    private TreatmentActLookup acts;
    private PdfService pdfService;
    private FileService files;
    private TaskService tasks;
    private InsuranceFormService service;
    private final Practitioner doctor = Practitioner.builder().id(UUID.randomUUID()).displayName("Dr Amrani").inpe("123456789").build();

    @BeforeEach
    void setUp() {
        forms = mock(InsuranceFormJpaRepository.class);
        when(forms.save(any())).thenAnswer(i -> {
            InsuranceForm f = i.getArgument(0);
            saved.removeIf(s -> s.getId().equals(f.getId()));
            saved.add(f);
            return f;
        });
        when(forms.findByIdAndPracticeId(any(), eq(PRACTICE))).thenAnswer(i -> saved.stream()
                .filter(f -> f.getId().equals(i.getArgument(0))).findFirst());
        patients = mock(PatientLookup.class);
        practitioners = mock(PractitionerService.class);
        when(practitioners.findIdByUser(ACTOR)).thenReturn(Optional.of(doctor.getId()));
        when(practitioners.require(PRACTICE, doctor.getId())).thenReturn(doctor);
        acts = mock(TreatmentActLookup.class);
        when(acts.byTreatmentIds(any())).thenReturn(Map.of(
                CONSULT_ACT, new CodedTreatment(CONSULT_ACT, "Consultation", "C", BigDecimal.ONE, "C 1", false),
                ODF_ACT, new CodedTreatment(ODF_ACT, "Traitement ODF (semestre)", "D629", new BigDecimal("90"), "D 90", true),
                FILLING, new CodedTreatment(FILLING, "Obturation", "D708", new BigDecimal("12"), "D 12", false)));
        pdfService = mock(PdfService.class);
        when(pdfService.render(eq("insurance-statement"), anyMap(), eq("fr"))).thenReturn("%PDF-statement".getBytes());
        LetterheadProvider letterheads = mock(LetterheadProvider.class);
        when(letterheads.forPractice(PRACTICE)).thenReturn(new Letterhead("Cabinet Bento", null, null, null, null, null,
                "12 bd Anfa", "Casablanca", "0522 00 00 00", null, null));
        files = mock(FileService.class);
        when(files.storeGenerated(eq(PRACTICE), eq(FileOwnerType.INSURANCE_FORM), any(), anyString(), eq("application/pdf"), any(), eq(ACTOR)))
                .thenAnswer(i -> StoredFile.builder().id(UUID.randomUUID()).build());
        DocumentNumbers numbers = mock(DocumentNumbers.class);
        when(numbers.next(eq(PRACTICE), eq("insurance_form"))).thenReturn(1L, 2L, 3L);
        tasks = mock(TaskService.class);
        when(tasks.createForDocument(any(), any(), any(), any(), any())).thenAnswer(i -> new TaskDtos.View(UUID.randomUUID(),
                "t", null, null, null, UserRole.ASSISTANT, ACTOR, null, Task.Priority.NORMAL, PATIENT, null, Task.Status.OPEN,
                null, false, Task.DocumentKind.INSURANCE_FORM, i.getArgument(4)));
        service = new InsuranceFormService(forms, patients, practitioners, acts, new FormLayouts(), new PdfFormFiller(),
                pdfService, letterheads, files, numbers, tasks, id -> ZoneId.of("Africa/Casablanca"),
                new ObjectMapper().findAndRegisterModules());
    }

    private void patient(UUID insurerId, String relation, String insuredName, String insuredCin) {
        when(patients.findInsurerOf(PATIENT)).thenReturn(Optional.ofNullable(insurer(insurerId)));
        when(patients.findIdentity(PATIENT)).thenReturn(Optional.of(new PatientIdentity(PATIENT, "P-00001", "Yasmine", "Bennani",
                LocalDate.of(2014, 3, 14), "F", null, "12, rue des Orangers", "0661234567", null, null, "123456789",
                insurerId, "Karim Bennani", "1234567", relation, insuredName, insuredCin)));
    }

    @Test
    void aPatientWithNoInsurerGetsNoForm() {
        patient(null, "SELF", null, null);

        assertThat(service.afterSession(PRACTICE, ACTOR, CONSULTATION, PATIENT,
                List.of(new SessionAct(CONSULT_ACT, "Consultation", null, new BigDecimal("250"), true)))).isEmpty();
        verify(forms, never()).save(any());
    }

    @Test
    void aSessionReportsWhatWasDoneAndAsksAgreementForTheOrthodonticsItProposes() {
        patient(CNOPS, "CHILD", "Karim Bennani", "BK123456");

        List<InsuranceFormDtos.Issued> issued = service.afterSession(PRACTICE, ACTOR, CONSULTATION, PATIENT, List.of(
                new SessionAct(CONSULT_ACT, "Consultation", null, new BigDecimal("250"), true),
                new SessionAct(ODF_ACT, "Traitement ODF, 1er semestre", "", new BigDecimal("3000"), false),
                // A filling proposed for next time needs no agreement: it is reported once done.
                new SessionAct(FILLING, "Obturation 36", "36", new BigDecimal("400"), false)));

        assertThat(issued).extracting(InsuranceFormDtos.Issued::purpose)
                .containsExactly(InsuranceForm.Purpose.EXECUTION, InsuranceForm.Purpose.PRIOR_AGREEMENT);
        assertThat(issued).allMatch(i -> i.formName().startsWith("CNOPS") && i.sent());
        InsuranceForm done = saved.get(0);
        assertThat(done.getFormCode()).isEqualTo("cnops-dentaire");
        assertThat(done.getTotal()).isEqualByComparingTo("250");
        assertThat(done.getPractitionerId()).isEqualTo(doctor.getId());
        assertThat(done.getConsultationId()).isEqualTo(CONSULTATION);
        assertThat(saved.get(1).getTotal()).isEqualByComparingTo("3000");

        InsuranceFormDtos.View view = service.get(PRACTICE, done.getId());
        assertThat(view.lines()).singleElement().satisfies(l -> {
            assertThat(l.code()).isEqualTo("C");
            assertThat(l.cotation()).isEqualTo("C 1");
            assertThat(l.date()).isNotNull();
        });
        // The proposed act is asked for, not dated as done.
        assertThat(service.get(PRACTICE, saved.get(1).getId()).lines()).singleElement()
                .satisfies(l -> assertThat(l.date()).isNull());
        assertThat(view.missing()).isEmpty();

        ArgumentCaptor<TaskDtos.Request> task = ArgumentCaptor.forClass(TaskDtos.Request.class);
        verify(tasks, times(2)).createForDocument(eq(PRACTICE), eq(ACTOR), task.capture(), eq(Task.DocumentKind.INSURANCE_FORM), any());
        assertThat(task.getAllValues()).allSatisfy(r -> {
            assertThat(r.assigneeRole()).isEqualTo(UserRole.ASSISTANT);
            assertThat(r.patientId()).isEqualTo(PATIENT);
        });
        assertThat(task.getAllValues().get(0).title()).isEqualTo("Feuille de soins CNOPS — BENNANI Yasmine");
        assertThat(task.getAllValues().get(1).title()).startsWith("Entente préalable CNOPS");
        assertThat(task.getAllValues().get(0).description()).contains("Dr Amrani").contains("250,00 DH");
    }

    @Test
    void anInsurerWhoseSheetOrthoFlowDoesNotFillGetsTheStatementOfActsInstead() {
        patient(AXA, "SELF", null, null);

        InsuranceFormDtos.View view = service.create(PRACTICE, ACTOR, new CreateRequest(PATIENT, InsuranceForm.Purpose.EXECUTION,
                LocalDate.of(2026, 10, 9), null, List.of(new LineRequest(null, "36", FILLING, null, null, null, new BigDecimal("400"))),
                null, null, false, null));

        assertThat(view.formCode()).isEqualTo(FormLayouts.GENERIC);
        assertThat(view.insurerName()).isEqualTo("AXA Assurance Maroc");
        assertThat(view.lines()).singleElement().satisfies(l -> {
            assertThat(l.code()).isEqualTo("D708");
            assertThat(l.label()).isEqualTo("Obturation");
            assertThat(l.date()).isEqualTo(LocalDate.of(2026, 10, 9));
        });
        verify(pdfService).render(eq("insurance-statement"), anyMap(), eq("fr"));
        verify(tasks, never()).createForDocument(any(), any(), any(), any(), any());
    }

    @Test
    void whatTheRecordLacksForTheFormIsNamedRatherThanGuessed() {
        when(patients.findIdentity(PATIENT)).thenReturn(Optional.of(new PatientIdentity(PATIENT, "P-00001", "Yasmine", "Bennani",
                null, null, null, null, null, null, null, null, CNOPS, null, null, "CHILD", null, null)));
        when(patients.findInsurerOf(PATIENT)).thenReturn(Optional.of(insurer(CNOPS)));
        when(practitioners.findIdByUser(ACTOR)).thenReturn(Optional.empty());

        assertThat(service.preview(PRACTICE, ACTOR, PATIENT, null).missing()).containsExactly("INSURANCE_NUMBER", "AFFILIATION_NUMBER",
                "INSURED_NAME", "INSURED_CIN", "DATE_OF_BIRTH", "GENDER", "PRACTITIONER_INPE");
    }

    @Test
    void aFormIsRefreshedFromTheRecordOnlyUntilItIsPrinted() {
        patient(CNOPS, "SELF", null, null);
        InsuranceFormDtos.View view = service.create(PRACTICE, ACTOR, new CreateRequest(PATIENT, InsuranceForm.Purpose.EXECUTION,
                null, null, List.of(new LineRequest(null, null, null, "C", "Consultation", "C 1", new BigDecimal("250"))),
                null, null, true, null));
        verify(tasks).createForDocument(eq(PRACTICE), eq(ACTOR), any(), eq(Task.DocumentKind.INSURANCE_FORM), eq(view.id()));

        service.refresh(PRACTICE, ACTOR, view.id());
        verify(files, times(2)).storeGenerated(any(), any(), eq(view.id()), anyString(), anyString(), any(), any());

        service.markPrinted(PRACTICE, view.id());
        assertThatThrownBy(() -> service.refresh(PRACTICE, ACTOR, view.id())).isInstanceOf(ConflictException.class);
        assertThat(service.markHandedOver(PRACTICE, view.id()).status()).isEqualTo(InsuranceForm.Status.HANDED_OVER);
    }

    @Test
    void theStatementOfActsRendersWithTheRealTemplate() throws Exception {
        patient(AXA, "CHILD", "Karim Bennani", null);
        PdfService real = new PdfService(new com.orthoflow.export.infrastructure.PdfTemplateConfig().pdfTemplateEngine());
        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        service = new InsuranceFormService(forms, patients, practitioners, acts, new FormLayouts(), new PdfFormFiller(), real,
                letterheadsFor(), files, numbersFrom(7), tasks, id -> ZoneId.of("Africa/Casablanca"),
                new ObjectMapper().findAndRegisterModules());

        service.create(PRACTICE, ACTOR, new CreateRequest(PATIENT, InsuranceForm.Purpose.PRIOR_AGREEMENT, LocalDate.of(2026, 10, 9),
                null, List.of(new LineRequest(null, "", ODF_ACT, null, null, null, new BigDecimal("3000"))), null, null, false, null));

        verify(files).storeGenerated(any(), any(), any(), anyString(), anyString(), stored.capture(), any());
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(stored.getValue())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("Relevé des actes dentaires").contains("Demande d'entente préalable")
                    .contains("AXA Assurance Maroc").contains("Karim Bennani").contains("BENNANI Yasmine")
                    .contains("D629").contains("D 90").contains("3 000,00").contains("enfant").contains("INPE : 123456789")
                    .contains("FSA-2026-00007");
        }
    }

    private static LetterheadProvider letterheadsFor() {
        LetterheadProvider letterheads = mock(LetterheadProvider.class);
        when(letterheads.forPractice(PRACTICE)).thenReturn(new Letterhead("Cabinet Bento", null, null, null, null, null,
                "12 bd Anfa", "Casablanca", "0522 00 00 00", null, null));
        return letterheads;
    }

    private static DocumentNumbers numbersFrom(long n) {
        DocumentNumbers numbers = mock(DocumentNumbers.class);
        when(numbers.next(eq(PRACTICE), eq("insurance_form"))).thenReturn(n);
        return numbers;
    }

    private static InsurerRef insurer(UUID id) {
        if (CNOPS.equals(id)) return new InsurerRef(CNOPS, "CNOPS", "CNOPS", "PUBLIC", null);
        if (AXA.equals(id)) return new InsurerRef(AXA, "AXA", "AXA Assurance Maroc", "PRIVATE", null);
        return null;
    }
}
