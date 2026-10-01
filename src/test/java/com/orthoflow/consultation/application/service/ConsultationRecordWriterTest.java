package com.orthoflow.consultation.application.service;

import static com.orthoflow.consultation.application.service.ConsultationFixtures.ACTOR;
import static com.orthoflow.consultation.application.service.ConsultationFixtures.PATIENT;
import static com.orthoflow.consultation.application.service.ConsultationFixtures.VOICE_SESSION;
import static com.orthoflow.consultation.application.service.ConsultationFixtures.consultation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.clinical.application.dto.AddAllergyRequest;
import com.orthoflow.clinical.application.dto.AddMedicalHistoryRequest;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.clinical.domain.model.NoteCategory;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.consultation.application.dto.CommitConsultationRequest;
import com.orthoflow.consultation.application.dto.CommitConsultationRequest.*;
import com.orthoflow.consultation.application.service.ConsultationFixtures.InMemoryConsultations;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.patient.application.dto.PatientDemographicsUpdate;
import com.orthoflow.patient.application.service.PatientService;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.application.service.AppointmentService;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsultationRecordWriterTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private InMemoryConsultations repo;
    private PatientService patients;
    private ClinicalRecordService clinical;
    private AppointmentService appointments;
    private ConsultationRecordWriter writer;
    private Consultation consultation;
    private ConsultationExtractionProperties properties;

    @BeforeEach
    void setUp() {
        repo = new InMemoryConsultations();
        patients = mock(PatientService.class);
        clinical = mock(ClinicalRecordService.class);
        appointments = mock(AppointmentService.class);
        properties = new ConsultationExtractionProperties();
        properties.setRetainTranscript(true);
        writer = new ConsultationRecordWriter(repo, patients, clinical, appointments, json, properties);
        when(appointments.createAppointment(any()))
                .thenReturn(AppointmentResponse.builder().id(UUID.randomUUID()).build());
        consultation = repo.save(consultation(ConsultationStatus.REVIEW));
        consultation.setTranscript("Bonjour docteur… toute la conversation");
        consultation.setReviewState("{\"v\":1}");
    }

    private static CommitConsultationRequest full() {
        CommitConsultationRequest r = new CommitConsultationRequest();

        PatientChanges patient = new PatientChanges();
        patient.setPhone("0612345678");
        patient.setCin("BK123456");
        patient.setInsuranceProvider("CNOPS");
        r.setPatient(patient);

        AllergyItem allergy = new AllergyItem();
        allergy.setSubstance("pénicilline");
        allergy.setReaction("urticaire");
        allergy.setSeverity("MODERATE");
        r.setAllergies(List.of(allergy));

        HistoryItem history = new HistoryItem();
        history.setCategory("CONDITION");
        history.setLabel("Diabète");
        r.setMedicalHistory(List.of(history));

        ActiveTreatmentItem drug = new ActiveTreatmentItem();
        drug.setLabel("Kardegic");
        drug.setType("MEDICATION");
        drug.setDetail("75 mg");
        ActiveTreatmentItem appliance = new ActiveTreatmentItem();
        appliance.setLabel("Appareil fixe");
        appliance.setType("DENTAL");
        r.setActiveTreatments(List.of(drug, appliance));

        r.setChiefComplaint("Douleur sur la 16");
        PlanLine detartrage = new PlanLine();
        detartrage.setLabel("Détartrage");
        detartrage.setPrice(new BigDecimal("300.00"));
        PlanLine composite = new PlanLine();
        composite.setLabel("Composite");
        composite.setTeeth("16");
        composite.setPrice(new BigDecimal("400"));
        composite.setQuantity(2);
        r.setTreatmentPlan(List.of(detartrage, composite));

        AppointmentSlot slot = new AppointmentSlot();
        slot.setDateTime(OffsetDateTime.parse("2026-10-16T10:00:00+01:00"));
        slot.setDurationMinutes(45);
        r.setNextAppointment(slot);

        r.setReport("Compte rendu relu.");
        r.setApprovedAuditIds(List.of(UUID.randomUUID()));
        return r;
    }

    @Test
    void writesWhoThePatientIsAsAPartialChangeNotAReplacement() {
        writer.write(consultation.getId(), full(), ACTOR);

        ArgumentCaptor<PatientDemographicsUpdate> sent = ArgumentCaptor.forClass(PatientDemographicsUpdate.class);
        verify(patients).applyDemographics(eq(PATIENT), sent.capture());
        assertThat(sent.getValue().phone()).isEqualTo("0612345678");
        assertThat(sent.getValue().cin()).isEqualTo("BK123456");
        assertThat(sent.getValue().firstName()).isNull();
    }

    @Test
    void filesEveryAllergyMarkedAsComingFromAConsultation() {
        writer.write(consultation.getId(), full(), ACTOR);

        ArgumentCaptor<AddAllergyRequest> sent = ArgumentCaptor.forClass(AddAllergyRequest.class);
        verify(clinical).addAllergy(eq(PATIENT), sent.capture(), eq(ACTOR));
        assertThat(sent.getValue().getSubstance()).isEqualTo("pénicilline");
        assertThat(sent.getValue().getSeverity()).isEqualTo("MODERATE");
        assertThat(sent.getValue().getSource()).isEqualTo("consultation").hasSizeLessThanOrEqualTo(20);
        assertThat(sent.getValue().getSessionId()).isEqualTo(VOICE_SESSION);
    }

    @Test
    void whatThePatientIsGoingThroughNowIsFiledAsHistoryWithTheRightCategory() {
        writer.write(consultation.getId(), full(), ACTOR);

        ArgumentCaptor<AddMedicalHistoryRequest> sent = ArgumentCaptor.forClass(AddMedicalHistoryRequest.class);
        verify(clinical, times(3)).addMedicalHistory(eq(PATIENT), sent.capture(), eq(ACTOR));
        List<AddMedicalHistoryRequest> filed = sent.getAllValues();
        assertThat(filed).extracting(AddMedicalHistoryRequest::getLabel).containsExactly("Diabète", "Kardegic", "Appareil fixe");
        assertThat(filed).extracting(AddMedicalHistoryRequest::getCategory)
                .containsExactly("CONDITION", "MEDICATION", "DENTAL_HISTORY");
        assertThat(filed.get(1).getDetail()).isEqualTo("Traitement en cours — 75 mg");
        assertThat(filed.get(2).getDetail()).isEqualTo("Traitement en cours");
        assertThat(filed).allSatisfy(h -> assertThat(h.getSource()).isEqualTo("consultation"));
    }

    @Test
    void filesTheComplaintThePlanTheReportAndTheRawTranscriptAsNotesOfTheRightCategories() {
        writer.write(consultation.getId(), full(), ACTOR);

        ArgumentCaptor<NoteCategory> category = ArgumentCaptor.forClass(NoteCategory.class);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(clinical, times(4)).saveSessionNote(eq(PATIENT), eq(VOICE_SESSION), category.capture(), content.capture(), eq(ACTOR), eq("consultation"));

        assertThat(category.getAllValues()).containsExactlyInAnyOrder(
                NoteCategory.CHIEF_COMPLAINT, NoteCategory.TREATMENT_PLAN,
                NoteCategory.CONSULTATION_REPORT, NoteCategory.CONSULTATION_TRANSCRIPT);
        assertThat(content.getAllValues()).contains("Douleur sur la 16", "Compte rendu relu.",
                "Bonjour docteur… toute la conversation");
    }

    @Test
    void withoutRetentionTheTranscriptIsNeitherFiledNorKeptOnTheRow() {
        properties.setRetainTranscript(false);
        consultation.setDraft("{\"source\":\"groq:x\"}");

        Consultation saved = writer.write(consultation.getId(), full(), ACTOR);

        ArgumentCaptor<NoteCategory> category = ArgumentCaptor.forClass(NoteCategory.class);
        verify(clinical, times(3)).saveSessionNote(eq(PATIENT), eq(VOICE_SESSION), category.capture(), any(), eq(ACTOR), eq("consultation"));
        assertThat(category.getAllValues()).doesNotContain(NoteCategory.CONSULTATION_TRANSCRIPT);
        assertThat(saved.getTranscript()).isNull();
        assertThat(saved.getDraft()).isNull();
        // What the doctor validated is the record, and stays printable.
        assertThat(saved.getReviewed()).contains("pénicilline");
        assertThat(saved.getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
    }

    @Test
    void thePlanNoteListsEachActWithItsPriceAndTheTotal() {
        String plan = ConsultationRecordWriter.planText(full());

        assertThat(plan).contains("• Détartrage — 300 MAD")
                .contains("• Composite (dent 16) ×2 — 400 MAD")
                .endsWith("Total : 1100 MAD");
    }

    @Test
    void aPlanWithAnUnpricedActSaysTheTotalIsPartial() {
        CommitConsultationRequest r = new CommitConsultationRequest();
        PlanLine priced = new PlanLine();
        priced.setLabel("Détartrage");
        priced.setPrice(new BigDecimal("300"));
        PlanLine unpriced = new PlanLine();
        unpriced.setLabel("Couronne");
        r.setTreatmentPlan(List.of(priced, unpriced));

        assertThat(ConsultationRecordWriter.planText(r)).contains("• Couronne\n").endsWith("Total partiel : 300 MAD");
    }

    @Test
    void booksTheNextAppointmentForThePatient() {
        UUID appointmentId = UUID.randomUUID();
        AppointmentResponse booked = AppointmentResponse.builder().id(appointmentId).build();
        when(appointments.createAppointment(any())).thenReturn(booked);

        Consultation saved = writer.write(consultation.getId(), full(), ACTOR);

        ArgumentCaptor<AppointmentRequest> sent = ArgumentCaptor.forClass(AppointmentRequest.class);
        verify(appointments).createAppointment(sent.capture());
        assertThat(sent.getValue().getPatientId()).isEqualTo(PATIENT);
        assertThat(sent.getValue().getDurationMinutes()).isEqualTo(45);
        assertThat(sent.getValue().getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(sent.getValue().getType()).isEqualTo("Contrôle");
        assertThat(saved.getAppointmentId()).isEqualTo(appointmentId);
    }

    @Test
    void marksTheConsultationSavedAndKeepsWhatWasSignedOffWithoutTheChartPlumbing() throws Exception {
        Consultation saved = writer.write(consultation.getId(), full(), ACTOR);

        assertThat(saved.getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getReport()).isEqualTo("Compte rendu relu.");
        JsonNode reviewed = json.readTree(saved.getReviewed());
        assertThat(reviewed.path("allergies").get(0).path("substance").asText()).isEqualTo("pénicilline");
        assertThat(reviewed.path("treatmentPlan")).hasSize(2);
        assertThat(reviewed.has("approvedAuditIds")).isFalse();
        assertThat(reviewed.has("amendments")).isFalse();
        assertThat(saved.getReviewState()).isNull();
        // With retention on (testing), the transcript stays on the consultation.
        assertThat(saved.getTranscript()).isNotBlank();
    }

    @Test
    void anEmptyConsultationWritesNoNotesAndNoAppointment() {
        consultation.setTranscript("");

        writer.write(consultation.getId(), new CommitConsultationRequest(), ACTOR);

        verify(clinical, never()).saveSessionNote(any(), any(), any(), any(), any(), any());
        verify(appointments, never()).createAppointment(any());
        verify(patients, never()).applyDemographics(any(), any());
        assertThat(consultation.getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
    }

    @Test
    void withoutADictatedSessionTheNotesShareTheConsultationsOwnId() {
        consultation.setVoiceSessionId(null);

        writer.write(consultation.getId(), full(), ACTOR);

        verify(clinical, times(4)).saveSessionNote(eq(PATIENT), eq(consultation.getId()), any(), any(), eq(ACTOR), eq("consultation"));
    }

    @Test
    void refusesAConsultationThatIsNotInReview() {
        consultation.setStatus(ConsultationStatus.COMPLETED);

        assertThatThrownBy(() -> writer.write(consultation.getId(), full(), ACTOR))
                .isInstanceOf(ValidationException.class);
        verify(clinical, never()).addAllergy(any(), any(), any());
    }
}
