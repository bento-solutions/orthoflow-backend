package com.orthoflow.clinical.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.clinical.application.dto.AllergyResponse;
import com.orthoflow.clinical.application.dto.MedicalHistoryResponse;
import com.orthoflow.clinical.application.dto.PeriodontalAssessmentResponse;
import com.orthoflow.clinical.application.dto.PeriodontalStatusResponse;
import com.orthoflow.clinical.application.dto.ToothFindingResponse;
import com.orthoflow.clinical.application.dto.TreatmentPassport;
import com.orthoflow.clinical.domain.model.ChartType;
import com.orthoflow.clinical.domain.model.DentalChart;
import com.orthoflow.clinical.domain.model.ToothState;
import com.orthoflow.clinical.domain.repository.DentalChartRepository;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.export.infrastructure.PdfTemplateConfig;
import com.orthoflow.patient.application.port.PatientIdentity;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.treatment.domain.model.PatientTreatment;
import com.orthoflow.treatment.domain.model.PatientTreatmentStatus;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.repository.PatientTreatmentRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a patient carries to the next dentist: right content, right order, no
 * contact details, and a record that it left the system.
 */
class TreatmentPassportServiceTest {

    private static final UUID PRACTICE = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private ClinicalRecordService clinical;
    private PeriodontalService periodontal;
    private PatientLookup patients;
    private PatientTreatmentRepository treatments;
    private ActivityLog activity;
    private DentalChartRepository charts;
    private TreatmentPassportService service;

    private static ToothFindingResponse finding(String fdi, String code, String kind, String surface, String status,
                                                LocalDate performedOn, String origin, String provider) {
        return ToothFindingResponse.builder().id(UUID.randomUUID()).fdi(fdi).findingCode(code).kind(kind)
                .surface(surface).status(status).performedOn(performedOn).origin(origin).providerName(provider)
                .source("manual").createdAt(OffsetDateTime.parse("2026-10-10T09:00:00Z"))
                .updatedAt(OffsetDateTime.parse("2026-10-10T09:00:00Z")).build();
    }

    /** The same finding, said with a doubt: the dentist suspects it and has not confirmed it. */
    private static ToothFindingResponse suspected(ToothFindingResponse f) {
        return new ToothFindingResponse(f.id(), f.fdi(), f.findingCode(), f.kind(), f.surface(), f.severity(), f.note(),
                "SUSPECTED", f.performedOn(), f.origin(), f.providerName(), f.status(), f.source(), f.sessionId(),
                f.createdAt(), f.updatedAt());
    }

    @BeforeEach
    void setUp() {
        clinical = mock(ClinicalRecordService.class);
        periodontal = mock(PeriodontalService.class);
        patients = mock(PatientLookup.class);
        treatments = mock(PatientTreatmentRepository.class);
        activity = mock(ActivityLog.class);
        LetterheadProvider letterheads = p -> new Letterhead("Cabinet Atlas", null, null, null, null, null,
                "12 rue des Orangers", "Casablanca", "0522000000", "contact@atlas.test", null);

        when(patients.findIdentity(PATIENT)).thenReturn(Optional.of(new PatientIdentity(PATIENT, "P-1", "Sara",
                "Benziane", LocalDate.of(1990, 5, 12), "F", "AB123456", "secret address", "0612345678",
                "sara@example.test", "CNSS", "999", null, null, null, null, null, null)));

        when(clinical.listFindingHistory(PATIENT)).thenReturn(List.of(
                finding("16", "caries", "CONDITION", "distal", "ACTIVE", null, "THIS_CLINIC", null),
                finding("16", "existing_amalgam", "EXISTING", "occlusal", "ACTIVE", LocalDate.of(2019, 4, 2), "EXTERNAL", "Dr Benani, Casablanca"),
                finding("26", "existing_crown", "EXISTING", null, "ACTIVE", LocalDate.of(2021, 11, 20), "EXTERNAL", "Dr Alaoui"),
                finding("36", "filling_required", "TREATMENT_REQUIRED", "mesial-occlusal-distal-buccal", "ACTIVE", null, "THIS_CLINIC", null),
                finding("46", "caries", "CONDITION", "mesial", "RESOLVED", LocalDate.of(2025, 2, 3), "THIS_CLINIC", null)));
        when(clinical.listAllergies(PATIENT)).thenReturn(List.of(AllergyResponse.builder().substance("Penicillin").reaction("Rash").severity("MODERATE").build()));
        when(clinical.listMedicalHistory(PATIENT)).thenReturn(List.of(MedicalHistoryResponse.builder().category("CONDITION").label("Diabetes type 2").detail("on metformin").build()));
        when(periodontal.status(PATIENT)).thenReturn(PeriodontalStatusResponse.builder().current(List.of(
                PeriodontalAssessmentResponse.builder().region("UPPER_FRONT").condition("HEALTHY").assessedOn(LocalDate.of(2026, 10, 1)).build(),
                PeriodontalAssessmentResponse.builder().region("LOWER_LEFT").condition("PERIODONTITIS").stage(2).assessedOn(LocalDate.of(2026, 10, 1)).build())).history(List.of()).build());

        PatientTreatment done = PatientTreatment.builder().treatment(Treatment.builder().name("Scaling").actCode("D 20").build())
                .teeth("").status(PatientTreatmentStatus.COMPLETED).endDate(LocalDate.of(2026, 9, 15)).doctorName("Dr Idrissi").build();
        PatientTreatment ongoing = PatientTreatment.builder().treatment(Treatment.builder().name("Root canal").build())
                .teeth("37").status(PatientTreatmentStatus.ACTIVE).startDate(LocalDate.of(2026, 10, 5)).build();
        PatientTreatment cancelled = PatientTreatment.builder().treatment(Treatment.builder().name("Whitening").build())
                .teeth("11").status(PatientTreatmentStatus.CANCELLED).build();
        when(treatments.findByPatientId(PATIENT)).thenReturn(List.of(done, ongoing, cancelled));

        charts = mock(DentalChartRepository.class);
        DentalChart chart = DentalChart.builder().patientId(PATIENT).chartType(ChartType.adult).build();
        chart.getToothStates().add(ToothState.builder().chart(chart).fdi("16").status("caries").build());
        chart.getToothStates().add(ToothState.builder().chart(chart).fdi("26").status("crown").build());
        chart.getToothStates().add(ToothState.builder().chart(chart).fdi("47").status("missing").build());
        when(charts.findByPatientId(PATIENT)).thenReturn(Optional.of(chart));

        service = new TreatmentPassportService(clinical, periodontal, patients, letterheads, treatments, charts,
                new PdfService(new PdfTemplateConfig().pdfTemplateEngine()), activity);
    }

    @Test
    void anUnknownPatientIsNotFound() {
        assertThatThrownBy(() -> service.build(PRACTICE, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theTeethAreTheStateNowAndWorkStillOwedIsKeptApart() {
        TreatmentPassport p = service.build(PRACTICE, PATIENT);

        assertThat(p.teeth()).extracting(t -> t.fdi() + ":" + t.findingCode())
                .containsExactly("16:caries", "16:existing_amalgam", "26:existing_crown");
        assertThat(p.planned()).extracting(w -> w.outcome() + ":" + (w.code() != null ? w.code() : w.name()))
                .containsExactlyInAnyOrder("REQUIRED:filling_required", "IN_PROGRESS:Root canal");
    }

    @Test
    void theLogIsWorkDoneNewestFirstWithUnknownDatesLast() {
        TreatmentPassport p = service.build(PRACTICE, PATIENT);

        assertThat(p.log()).extracting(w -> w.outcome() + ":" + (w.code() != null ? w.code() : w.name()))
                .containsExactly("COMPLETED:Scaling", "TREATED:caries", "IN_PLACE:existing_crown", "IN_PLACE:existing_amalgam");
        var amalgam = p.log().get(3);
        assertThat(amalgam.origin()).isEqualTo("EXTERNAL");
        assertThat(amalgam.provider()).isEqualTo("Dr Benani, Casablanca");
        assertThat(amalgam.surfaces()).containsExactly("occlusal");
        assertThat(p.log().get(0).actCode()).isEqualTo("D 20");
    }

    @Test
    void aCancelledTreatmentAppearsNowhere() {
        TreatmentPassport p = service.build(PRACTICE, PATIENT);

        assertThat(p.log()).noneMatch(w -> "Whitening".equals(w.name()));
        assertThat(p.planned()).noneMatch(w -> "Whitening".equals(w.name()));
    }

    @Test
    void itCarriesWhatTheNextDentistNeedsAndNothingAboutContactOrMoney() throws Exception {
        TreatmentPassport p = service.build(PRACTICE, PATIENT);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = mapper.writeValueAsString(p);

        assertThat(p.format()).isEqualTo("orthoflow.treatment-passport");
        assertThat(p.version()).isEqualTo(1);
        assertThat(p.allergies()).extracting(a -> a.substance()).containsExactly("Penicillin");
        assertThat(p.medicalHistory()).extracting(h -> h.label()).containsExactly("Diabetes type 2");
        assertThat(p.gums()).extracting(g -> g.region() + ":" + g.condition()).containsExactlyInAnyOrder("UPPER_FRONT:HEALTHY", "LOWER_LEFT:PERIODONTITIS");
        assertThat(json).contains("Benziane").contains("1990-05-12");
        assertThat(json).doesNotContain("0612345678").doesNotContain("AB123456").doesNotContain("secret address")
                .doesNotContain("sara@example.test").doesNotContain("CNSS");
    }

    @Test
    void previewingLeavesNoTraceButHandingOverDoes() {
        service.build(PRACTICE, PATIENT);
        verify(activity, never()).record(any(), any(), any(), any(), any());

        service.export(PRACTICE, PATIENT);
        verify(activity).record(eq(PRACTICE), eq("PATIENT"), eq(PATIENT), eq("PASSPORT_EXPORTED"), any());

        service.pdf(PRACTICE, PATIENT, "fr");
        verify(activity, org.mockito.Mockito.times(2)).record(eq(PRACTICE), eq("PATIENT"), eq(PATIENT), eq("PASSPORT_EXPORTED"), any());
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void thePrintedPassportIsInTheChosenLanguage() throws Exception {
        String fr = textOf(service.pdf(PRACTICE, PATIENT, "fr"));
        assertThat(fr).contains("Passeport de soins dentaires").contains("Sara Benziane").contains("Cabinet Atlas")
                .contains("Obturation amalgame (O)").contains("Dr Benani, Casablanca").contains("Penicillin")
                .contains("Parodontite").contains("Journal des soins");

        String en = textOf(service.pdf(PRACTICE, PATIENT, "en"));
        assertThat(en).contains("Treatment passport").contains("Amalgam filling (O)").contains("Filling needed (MODB)")
                .contains("Elsewhere").contains("02/04/2019");
        assertThat(en).doesNotContain("0612345678").doesNotContain("AB123456");
    }

    @Test
    void theArabicPassportRenders() throws Exception {
        byte[] ar = service.pdf(PRACTICE, PATIENT, "ar");

        assertThat(new String(ar, 0, 5)).isEqualTo("%PDF-");
        assertThat(textOf(ar)).isNotBlank();
    }

    @Test
    void theChartPutsWhatWasAdministeredFirstAndLettersEveryTooth() {
        var chart = service.build(PRACTICE, PATIENT).chart();

        assertThat(chart.dentition()).isEqualTo("adult");
        assertThat(chart.legend()).extracting(e -> e.key() + ":" + e.group() + ":" + (e.code() != null ? e.code() : e.name()))
                .containsExactly(
                        "A:DONE:caries", "B:DONE:existing_amalgam", "C:DONE:existing_crown", "D:DONE:Scaling",
                        "E:CONDITION:caries", "F:PLANNED:filling_required", "G:PLANNED:Root canal");
        assertThat(chart.teeth()).extracting(t -> t.fdi() + ":" + String.join("", t.keys()) + ":" + t.status() + ":" + t.done() + ":" + t.owed())
                .containsExactly(
                        "16:BE:caries:true:false", "26:C:crown:true:false", "36:F:present:false:true", "37:G:present:false:true",
                        "46:A:present:true:false", "47::missing:false:false");
    }

    @Test
    void aTreatedConditionIsDrawnAsTreatmentNotAsTheCondition() {
        var legend = service.build(PRACTICE, PATIENT).chart().legend();

        assertThat(legend.get(0).symbol()).isEqualTo("ring");
        assertThat(legend.get(1).symbol()).isEqualTo("solid");
        assertThat(legend.get(2).symbol()).isEqualTo("hatch");
        assertThat(legend.get(4).symbol()).isEqualTo("stipple");
        assertThat(legend.get(5).symbol()).isEqualTo("dashed");
    }

    @Test
    void theChartDocumentHasTheLegendInWordsAndIsRecorded() throws Exception {
        String fr = textOf(service.chartPdf(PRACTICE, PATIENT, "fr"));

        assertThat(fr).contains("Schéma dentaire").contains("Légende").contains("Soins réalisés").contains("Pathologies")
                .contains("Soins restant à réaliser").contains("Couronne").contains("Dr Alaoui").contains("20/11/2021")
                .contains("Sara Benziane").contains("Droite du patient").contains("Toute la bouche");
        assertThat(fr).doesNotContain("0612345678");
        verify(activity).record(eq(PRACTICE), eq("PATIENT"), eq(PATIENT), eq("DENTAL_CHART_EXPORTED"), any());

        assertThat(textOf(service.chartPdf(PRACTICE, PATIENT, "en"))).contains("Treatments administered").contains("Crown");
        assertThat(textOf(service.chartPdf(PRACTICE, PATIENT, "ar"))).isNotBlank();
    }

    @Test
    void thePassportCarriesTheChart() throws Exception {
        assertThat(textOf(service.pdf(PRACTICE, PATIENT, "fr"))).contains("Schéma dentaire").contains("Légende");
    }

    @Test
    void aChildNeverChartedGetsThePrimaryChart() throws Exception {
        when(charts.findByPatientId(PATIENT)).thenReturn(Optional.empty());
        when(patients.findIdentity(PATIENT)).thenReturn(Optional.of(new PatientIdentity(PATIENT, "P-2", "Yassine",
                "Benziane", LocalDate.now().minusYears(6), "M", null, null, null, null, null, null, null, null, null, null, null, null)));
        when(clinical.listFindingHistory(PATIENT)).thenReturn(List.of(
                finding("54", "caries", "CONDITION", "occlusal", "ACTIVE", null, "THIS_CLINIC", null)));

        var chart = service.build(PRACTICE, PATIENT).chart();

        assertThat(chart.dentition()).isEqualTo("child");
        assertThat(textOf(service.chartPdf(PRACTICE, PATIENT, "fr"))).contains("Carie");
    }

    @Test
    void aSuspicionIsOnTheToothAsOneAndNeverAmongTheWorkInPlaceOrTheConditions() {
        when(treatments.findByPatientId(PATIENT)).thenReturn(List.of());
        when(clinical.listFindingHistory(PATIENT)).thenReturn(List.of(
                suspected(finding("16", "caries", "CONDITION", "distal", "ACTIVE", null, "THIS_CLINIC", null)),
                suspected(finding("26", "existing_crown", "EXISTING", null, "ACTIVE", null, "THIS_CLINIC", null)),
                finding("36", "existing_amalgam", "EXISTING", "occlusal", "ACTIVE", LocalDate.of(2019, 4, 2), "EXTERNAL", "Dr Benani")));

        TreatmentPassport p = service.build(PRACTICE, PATIENT);

        // On the tooth's line, each with how sure the dentist is: the next dentist reads a doubt as a doubt.
        assertThat(p.teeth()).extracting(t -> t.fdi() + ":" + t.findingCode() + ":" + t.certainty())
                .containsExactly("16:caries:SUSPECTED", "26:existing_crown:SUSPECTED", "36:existing_amalgam:null");
        // A crown nobody has confirmed is not work anyone can vouch for: it is not in the log.
        assertThat(p.log()).extracting(w -> w.outcome() + ":" + w.code()).containsExactly("IN_PLACE:existing_amalgam");
        // The legend lists the suspicions to be watched, apart from conditions and work in place.
        assertThat(p.chart().legend()).extracting(e -> e.key() + ":" + e.group() + ":" + e.code() + ":" + e.outcome())
                .containsExactly("A:DONE:existing_amalgam:IN_PLACE", "B:WATCH:caries:SUSPECTED", "C:WATCH:existing_crown:SUSPECTED");
        assertThat(p.chart().teeth()).filteredOn(t -> t.fdi().equals("16") || t.fdi().equals("26"))
                .extracting(t -> t.fdi() + ":" + String.join("", t.keys()) + ":" + t.done() + ":" + t.owed())
                .containsExactly("16:B:false:false", "26:C:false:false");
    }

    @Test
    void aSuspectedRequirementIsStillWorkOwedBecauseItWasAskedFor() {
        when(clinical.listFindingHistory(PATIENT)).thenReturn(List.of(
                suspected(finding("36", "filling_required", "TREATMENT_REQUIRED", "mesial", "ACTIVE", null, "THIS_CLINIC", null))));

        TreatmentPassport p = service.build(PRACTICE, PATIENT);

        assertThat(p.planned()).extracting(w -> w.outcome() + ":" + w.code()).contains("REQUIRED:filling_required");
        assertThat(p.chart().legend()).extracting(e -> e.group() + ":" + e.code()).contains("PLANNED:filling_required");
    }

    @Test
    void theDocumentsPrintASuspicionAsASuspicionInEveryLanguage() throws Exception {
        when(clinical.listFindingHistory(PATIENT)).thenReturn(List.of(
                suspected(finding("16", "caries", "CONDITION", "distal", "ACTIVE", null, "THIS_CLINIC", null))));

        String fr = textOf(service.pdf(PRACTICE, PATIENT, "fr"));
        assertThat(fr).contains("Carie").contains("suspecté");
        assertThat(textOf(service.chartPdf(PRACTICE, PATIENT, "fr"))).contains("suspecté");

        assertThat(textOf(service.pdf(PRACTICE, PATIENT, "en"))).contains("suspected");
        assertThat(textOf(service.pdf(PRACTICE, PATIENT, "ar"))).isNotBlank();
    }

    @Test
    void aFindingWithNoCertaintyIsPrintedAsAFact() throws Exception {
        String fr = textOf(service.pdf(PRACTICE, PATIENT, "fr"));

        assertThat(fr).doesNotContain("suspecté");
    }

    @Test
    void anUnknownLanguageFallsBackToFrench() throws Exception {
        assertThat(textOf(service.pdf(PRACTICE, PATIENT, "xx"))).contains("Passeport de soins dentaires");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anArabicTableIsBuiltBackToFrontSoItsFirstColumnLandsOnTheRight() {
        // The PDF renderer lays columns out left to right whatever the direction, so the
        // service hands the template the columns of an Arabic table in reverse.
        Letterhead letterhead = Letterhead.blank();
        TreatmentPassport passport = service.build(PRACTICE, PATIENT);

        var fr = (java.util.Map<String, Object>) service.model(passport, letterhead, TreatmentPassportLabels.of("fr")).get("logGrid");
        var ar = (java.util.Map<String, Object>) service.model(passport, letterhead, TreatmentPassportLabels.of("ar")).get("logGrid");

        List<String> frHeaders = (List<String>) fr.get("headers");
        List<String> arHeaders = (List<String>) ar.get("headers");
        assertThat(frHeaders).containsExactly("Date", "Dent", "Soin", "Réalisé par");
        assertThat(arHeaders).containsExactly("أُنجز عند", "العلاج", "السن", "التاريخ");

        // Every row follows its header, and the widths follow too.
        List<List<List<String>>> frRows = (List<List<List<String>>>) fr.get("rows");
        List<List<List<String>>> arRows = (List<List<List<String>>>) ar.get("rows");
        assertThat(frRows.get(0).get(0)).isEqualTo(arRows.get(0).get(3));
        assertThat(frRows.get(0).get(3)).isEqualTo(arRows.get(0).get(0));
        assertThat((List<String>) fr.get("widths")).containsExactly("24mm", "18mm", "", "");
        assertThat((List<String>) ar.get("widths")).containsExactly("", "", "18mm", "24mm");
    }
}
