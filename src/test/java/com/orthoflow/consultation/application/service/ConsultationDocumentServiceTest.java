package com.orthoflow.consultation.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.consultation.application.dto.ConsultationDocumentRequest;
import com.orthoflow.consultation.application.dto.ConsultationDocumentRequest.Field;
import com.orthoflow.consultation.application.dto.ConsultationDocumentRequest.Kind;
import com.orthoflow.consultation.application.dto.ConsultationDocumentRequest.Section;
import com.orthoflow.consultation.application.dto.ConsultationDocumentRequest.Table;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.export.infrastructure.PdfTemplateConfig;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsultationDocumentServiceTest {

    private static final UUID PRACTICE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();

    private PatientLookup patients;
    private FileService files;
    private ConsultationDocumentService service;

    @BeforeEach
    void setUp() {
        patients = mock(PatientLookup.class);
        files = mock(FileService.class);
        when(patients.exists(PATIENT)).thenReturn(true);
        when(files.storeGenerated(any(), any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(StoredFile.builder().id(FILE).build());
        LetterheadProvider letterheads = p -> new Letterhead("Cabinet Atlas", null, null, null, null, null,
                "12 rue des Orangers", "Casablanca", "0522000000", "contact@atlas.test", null);
        service = new ConsultationDocumentService(new PdfService(new PdfTemplateConfig().pdfTemplateEngine()),
                letterheads, patients, files);
    }

    @Test
    void theReportIsLaidOutAndKeptInThePatientsDocuments() throws IOException {
        var rendered = service.render(PRACTICE, ACTOR, report(Kind.REPORT, "Dr Sara Alaoui", sections()));

        String text = text(rendered.bytes());
        assertThat(text).contains("Compte rendu de consultation", "Salma Bennani", "Pénicilline",
                "Douleur à la mastication", "Document médical confidentiel");
        assertThat(text.toLowerCase()).contains("signature et cachet du praticien");
        assertThat(text).contains("Dr Sara Alaoui").doesNotContain("Dr Dr");
        assertThat(rendered.fileId()).isEqualTo(FILE);
        assertThat(rendered.fileName()).startsWith("compte-rendu-").endsWith(".pdf");
        verify(files).storeGenerated(eq(PRACTICE), eq(FileOwnerType.PATIENT_DOCUMENT), eq(PATIENT),
                eq(rendered.fileName()), eq("application/pdf"), eq(rendered.bytes()), eq(ACTOR));
    }

    @Test
    void theAssistantsSheetIsPrintedButNotFiled() throws IOException {
        Section plan = new Section("Plan de traitement", null, null, false, new Table(
                List.of("Soin", "Dent", "Qté", "Prix"),
                List.of(List.of("Couronne", "46", "1", "3 000 MAD")), "Total", "3 000 MAD", null));
        var rendered = service.render(PRACTICE, ACTOR, report(Kind.ASSISTANT_SHEET, null, List.of(plan)));

        assertThat(text(rendered.bytes())).contains("Couronne", "3 000 MAD", "Total");
        assertThat(rendered.fileId()).isNull();
        assertThat(rendered.fileName()).startsWith("fiche-assistante-");
        verify(files, never()).storeGenerated(any(), any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void markupTypedIntoAFieldStaysText() throws IOException {
        Section note = new Section("Compte rendu", "<b>gras</b> & <script>x</script>", null, false, null);
        var rendered = service.render(PRACTICE, ACTOR, report(Kind.REPORT, null, List.of(note)));

        assertThat(text(rendered.bytes())).contains("<b>gras</b> & <script>x</script>");
    }

    @Test
    void aShortReportKeepsItsSignatureOnTheFirstPage() throws IOException {
        var rendered = service.render(PRACTICE, ACTOR, report(Kind.REPORT, "Sara Alaoui", sections()));
        try (PDDocument doc = Loader.loadPDF(rendered.bytes())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void aLongReportRunsOverPagesAndStillSigns() throws IOException {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            many.add("Constatation numéro " + i);
        }
        List<Section> sections = new ArrayList<>(sections());
        sections.add(new Section("Constatations au schéma", null, many, false, null));
        var rendered = service.render(PRACTICE, ACTOR, report(Kind.REPORT, null, sections));
        try (PDDocument doc = Loader.loadPDF(rendered.bytes())) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            PDFTextStripper last = new PDFTextStripper();
            last.setStartPage(doc.getNumberOfPages());
            last.setEndPage(doc.getNumberOfPages());
            assertThat(last.getText(doc).toLowerCase()).contains("constatation numéro 89", "signature et cachet du praticien");
        }
    }

    @Test
    void anUnknownPatientIsNotFound() {
        UUID stranger = UUID.randomUUID();
        ConsultationDocumentRequest request = new ConsultationDocumentRequest(stranger, Kind.REPORT, "fr", "Compte rendu",
                null, null, List.of(), List.of(), null, null);
        assertThatThrownBy(() -> service.render(PRACTICE, ACTOR, request)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theDoctorIsNamedOnceWhateverTheAccountNameStartsWith() {
        assertThat(ConsultationDocumentService.doctorName("Dr Sara Alaoui")).isEqualTo("Dr Sara Alaoui");
        assertThat(ConsultationDocumentService.doctorName("dr. Sara Alaoui")).isEqualTo("Dr Sara Alaoui");
        assertThat(ConsultationDocumentService.doctorName("Docteur Sara Alaoui")).isEqualTo("Dr Sara Alaoui");
        assertThat(ConsultationDocumentService.doctorName("Sara Alaoui")).isEqualTo("Dr Sara Alaoui");
        assertThat(ConsultationDocumentService.doctorName("Drissi Amine")).isEqualTo("Dr Drissi Amine");
        assertThat(ConsultationDocumentService.doctorName("  ")).isNull();
    }

    private static ConsultationDocumentRequest report(Kind kind, String doctor, List<Section> sections) {
        return new ConsultationDocumentRequest(PATIENT, kind, "fr",
                kind == Kind.REPORT ? "Compte rendu de consultation" : "Fiche assistante", "25 novembre 2026", doctor,
                List.of(new Field("Patient", "Salma Bennani"), new Field("Âge", "32 ans"), new Field("CIN", ""),
                        new Field("Assurance", "CNOPS")),
                sections, "Signature et cachet du praticien", "Document médical confidentiel");
    }

    private static List<Section> sections() {
        return List.of(
                new Section("Motif de consultation", "Douleur à la mastication.\nDepuis une semaine.", null, false, null),
                new Section("Allergies", null, List.of("Pénicilline (urticaire)"), true, null),
                new Section("Plan de traitement", null, List.of("Couronne (dent 46)"), false, null));
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
