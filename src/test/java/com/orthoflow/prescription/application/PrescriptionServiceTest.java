package com.orthoflow.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.clinical.application.dto.AllergyResponse;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.export.infrastructure.PdfTemplateConfig;
import com.orthoflow.patient.application.port.PatientIdentity;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.IssueRequest;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.Line;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.TemplateView;
import com.orthoflow.prescription.application.dto.PrescriptionDtos.View;
import com.orthoflow.prescription.domain.model.Prescription;
import com.orthoflow.prescription.domain.model.PrescriptionTemplate;
import com.orthoflow.prescription.infrastructure.PrescriptionJpaRepository;
import com.orthoflow.prescription.infrastructure.PrescriptionTemplateJpaRepository;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Issuing an ordonnance, with the real PDF template, and adopting from the library. */
class PrescriptionServiceTest {

    private static final UUID PRACTICE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final Line AUGMENTIN = new Line("AUGMENTIN 1 G/125 MG", "Sachet", "Amoxicilline + acide clavulanique",
            "1 sachet 3 fois par jour pendant 7 jours");

    private PrescriptionJpaRepository prescriptions;
    private PrescriptionTemplateJpaRepository templates;
    private PrescriptionLibrary library;
    private ClinicalRecordService clinical;
    private FileService files;
    private PrescriptionService service;
    private final Practitioner doctor = Practitioner.builder().id(UUID.randomUUID()).displayName("Dr Amrani").inpe("123456789").build();

    @BeforeEach
    void setUp() {
        prescriptions = mock(PrescriptionJpaRepository.class);
        when(prescriptions.save(any())).thenAnswer(i -> i.getArgument(0));
        templates = mock(PrescriptionTemplateJpaRepository.class);
        when(templates.save(any())).thenAnswer(i -> i.getArgument(0));
        library = mock(PrescriptionLibrary.class);
        clinical = mock(ClinicalRecordService.class);
        PatientLookup patients = mock(PatientLookup.class);
        when(patients.findIdentity(PATIENT)).thenReturn(Optional.of(new PatientIdentity(PATIENT, "P-1", "Yasmine", "Bennani",
                LocalDate.of(2014, 3, 14), "F", null, null, null, null, null, null, null, null, null, "SELF", null, null)));
        PractitionerService practitioners = mock(PractitionerService.class);
        when(practitioners.findIdByUser(ACTOR)).thenReturn(Optional.of(doctor.getId()));
        when(practitioners.require(PRACTICE, doctor.getId())).thenReturn(doctor);
        LetterheadProvider letterheads = mock(LetterheadProvider.class);
        when(letterheads.forPractice(PRACTICE)).thenReturn(new Letterhead("Cabinet Bento", null, null, null, null, null,
                "12 bd Anfa", "Casablanca", null, null, null));
        files = mock(FileService.class);
        when(files.storeGenerated(eq(PRACTICE), eq(FileOwnerType.PRESCRIPTION), any(), anyString(), eq("application/pdf"), any(), eq(ACTOR)))
                .thenAnswer(i -> StoredFile.builder().id(UUID.randomUUID()).build());
        DocumentNumbers numbers = mock(DocumentNumbers.class);
        when(numbers.next(PRACTICE, "prescription")).thenReturn(4L);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        service = new PrescriptionService(prescriptions, templates, library, clinical, patients, practitioners,
                new PdfService(new PdfTemplateConfig().pdfTemplateEngine()), letterheads, files, numbers, jdbc,
                id -> ZoneId.of("Africa/Casablanca"), new ObjectMapper().findAndRegisterModules());
    }

    private void allergicTo(String substance) {
        when(clinical.listAllergies(PATIENT)).thenReturn(List.of(new AllergyResponse(UUID.randomUUID(), substance, null,
                "SEVERE", "manual", null, OffsetDateTime.now())));
    }

    @Test
    void aDrugRelatedToAnAllergyIsNotIssuedUntilThePrescriberConfirms() {
        allergicTo("pénicilline");
        IssueRequest request = new IssueRequest(PATIENT, null, null, null, List.of(AUGMENTIN), null, false);

        assertThatThrownBy(() -> service.issue(PRACTICE, ACTOR, request)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("AUGMENTIN");
        verify(prescriptions, never()).save(any());

        View issued = service.issue(PRACTICE, ACTOR, new IssueRequest(PATIENT, null, null, null, List.of(AUGMENTIN), null, true));
        assertThat(issued.warnings()).singleElement().satisfies(w -> assertThat(w.allergy()).isEqualTo("pénicilline"));
    }

    @Test
    void theOrdonnanceIsNumberedAndPrintedWithThePrescriberThePatientAndTheDrugs() throws Exception {
        when(clinical.listAllergies(PATIENT)).thenReturn(List.of());

        View issued = service.issue(PRACTICE, ACTOR, new IssueRequest(PATIENT, null, null, null, List.of(AUGMENTIN),
                "Ne pas fumer.\nAlimentation tiède.", false));

        assertThat(issued.number()).matches("ORD-\\d{4}-00004");
        assertThat(issued.practitionerName()).isEqualTo("Dr Amrani");
        assertThat(issued.status()).isEqualTo(Prescription.Status.ISSUED);
        ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
        verify(files).storeGenerated(any(), any(), any(), anyString(), anyString(), pdf.capture(), any());
        try (PDDocument doc = PDDocument.load(pdf.getValue())) {
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("ORDONNANCE").contains("Dr Amrani").contains("INPE 123456789").contains("BENNANI Yasmine")
                    .contains("AUGMENTIN 1 G/125 MG").contains("Amoxicilline + acide clavulanique")
                    .contains("1 sachet 3 fois par jour pendant 7 jours").contains("Ne pas fumer.").contains("Casablanca, le");
        }
    }

    @Test
    void anAdoptedLibraryEntryIsTheClinicsToReviewAndKeepsThePatientFacingAdviceOnly() {
        when(library.find("abces-dentaire")).thenReturn(Optional.of(new PrescriptionLibrary.Entry("abces-dentaire", "Abcès dentaire",
                "DENTAL", List.of(AUGMENTIN), "Le geste dentaire est essentiel.", "Fièvre élevée, trismus.",
                "Allergie : PYOSTACINE 500 mg.", "https://ordonnance.ma/ordonnance.php?id=62")));

        TemplateView t = service.adopt(PRACTICE, ACTOR, "abces-dentaire");

        assertThat(t.reviewed()).isFalse();
        assertThat(t.libraryCode()).isEqualTo("abces-dentaire");
        assertThat(t.category()).isEqualTo(PrescriptionTemplate.Category.DENTAL);
        assertThat(t.advice()).contains("Le geste dentaire").contains("Consulter en urgence en cas de : fièvre élevée")
                .doesNotContain("PYOSTACINE");
        assertThat(t.lines()).containsExactly(AUGMENTIN);
    }

    @Test
    void aChildsAgeIsGivenInYearsAndABabysInMonths() {
        assertThat(PrescriptionService.age(LocalDate.of(2014, 3, 14), LocalDate.of(2026, 10, 9))).isEqualTo("12 ans");
        assertThat(PrescriptionService.age(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 10, 9))).isEqualTo("10 mois");
        assertThat(PrescriptionService.age(null, LocalDate.of(2026, 10, 9))).isNull();
    }
}
