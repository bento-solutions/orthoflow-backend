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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.consultation.application.dto.ConsultationResponse;
import com.orthoflow.consultation.application.dto.ExtractionResponse;
import com.orthoflow.consultation.application.dto.StartConsultationRequest;
import com.orthoflow.consultation.application.service.ConsultationFixtures.InMemoryConsultations;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationDraft;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractor;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import com.orthoflow.voice.application.dto.CompleteVoiceSessionRequest;
import com.orthoflow.voice.application.dto.StartVoiceSessionRequest;
import com.orthoflow.voice.application.dto.VoiceSessionResponse;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import com.orthoflow.voice.application.service.VoiceAuditService;
import com.orthoflow.voice.application.service.VoiceSessionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsultationServiceTest {

    private InMemoryConsultations repo;
    private VoiceSessionService voiceSessions;
    private VoiceAuditService voiceAudit;
    private PatientLookup patients;
    private TreatmentRepository treatments;
    private ConsultationExtractor extractor;
    private ConsultationExtractionProperties properties;
    private ConsultationService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryConsultations();
        voiceSessions = mock(VoiceSessionService.class);
        voiceAudit = mock(VoiceAuditService.class);
        patients = mock(PatientLookup.class);
        treatments = mock(TreatmentRepository.class);
        extractor = mock(ConsultationExtractor.class);
        properties = new ConsultationExtractionProperties();
        properties.setEnabled(true);
        service = new ConsultationService(repo, voiceSessions, voiceAudit, patients, treatments, extractor, properties,
                new ObjectMapper());

        when(patients.exists(PATIENT)).thenReturn(true);
        when(voiceSessions.start(any(), eq(ACTOR))).thenReturn(session("ACTIVE"));
        when(voiceSessions.get(VOICE_SESSION)).thenReturn(session("ACTIVE"));
    }

    private static VoiceSessionResponse session(String status) {
        return VoiceSessionResponse.builder().id(VOICE_SESSION).patientId(PATIENT).actorId(ACTOR).status(status).build();
    }

    private static StartConsultationRequest start(boolean informed) {
        StartConsultationRequest request = new StartConsultationRequest();
        request.setPatientId(PATIENT);
        request.setLocale("fr-MA");
        request.setPatientInformed(informed);
        return request;
    }

    private Consultation open(ConsultationStatus status) {
        return repo.save(consultation(status));
    }

    // ── Switch and consent ──────────────────────────────────────────────

    @Test
    void everyRouteIsNotFoundWhileTheFeatureIsOff() {
        properties.setEnabled(false);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.start(start(true), ACTOR)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.extract(id, "x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.findOpen(PATIENT)).isInstanceOf(NotFoundException.class);
        assertThat(service.config().enabled()).isFalse();
    }

    @Test
    void recordingDoesNotStartUntilTheDoctorAttestsThePatientWasTold() {
        assertThatThrownBy(() -> service.start(start(false), ACTOR))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("told");
        assertThat(repo.rows).isEmpty();
        verify(voiceSessions, never()).start(any(), any());
    }

    @Test
    void startsInIntakeWithItsOwnDictatedExaminationSessionAndRecordsWhenTheDoctorAttested() {
        ConsultationResponse started = service.start(start(true), ACTOR);

        assertThat(started.status()).isEqualTo("INTAKE");
        assertThat(started.voiceSessionId()).isEqualTo(VOICE_SESSION);
        assertThat(started.patientInformedAt()).isNotNull();
        ArgumentCaptor<StartVoiceSessionRequest> sent = ArgumentCaptor.forClass(StartVoiceSessionRequest.class);
        verify(voiceSessions).start(sent.capture(), eq(ACTOR));
        assertThat(sent.getValue().getPatientId()).isEqualTo(PATIENT);
    }

    @Test
    void anUnknownPatientIsNotFound() {
        when(patients.exists(PATIENT)).thenReturn(false);

        assertThatThrownBy(() -> service.start(start(true), ACTOR)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aSecondConsultationCannotStartWhileOneIsUnfinished() {
        open(ConsultationStatus.EXAMINATION);

        assertThatThrownBy(() -> service.start(start(true), ACTOR)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aFinishedConsultationDoesNotBlockTheNextOne() {
        open(ConsultationStatus.COMPLETED);

        assertThat(service.start(start(true), ACTOR).status()).isEqualTo("INTAKE");
    }

    // ── Reading the conversation ────────────────────────────────────────

    @Test
    void extractionSavesTheTranscriptAndTheDraftSoACrashLosesNothing() {
        Consultation c = open(ConsultationStatus.INTAKE);
        ConsultationDraft draft = ConsultationDraft.empty("rules");
        when(extractor.extract(eq("bonjour"), any(), any())).thenReturn(
                new ConsultationExtractor.Extraction(draft, "extraction-disabled", false));

        ExtractionResponse response = service.extract(c.getId(), "bonjour");

        assertThat(response.error()).isEqualTo("extraction-disabled");
        assertThat(repo.rows.get(c.getId()).getTranscript()).isEqualTo("bonjour");
        assertThat(repo.rows.get(c.getId()).getDraft()).contains("\"source\":\"rules\"");
    }

    @Test
    void aFailedReadingKeepsTheLastModelReadingInsteadOfTheRulesStandIn() {
        Consultation c = open(ConsultationStatus.EXAMINATION);
        ConsultationDraft model = new ConsultationDraft(ConsultationDraft.PatientFields.empty(),
                new ConsultationDraft.Quoted<>("Douleur", "j'ai mal"), List.of(), List.of(), List.of(),
                List.of(new ConsultationDraft.PlanItem("plan:couronne:36", "Couronne", null, null, "36",
                        new BigDecimal("3000"), "SPOKEN", null, "une couronne")),
                null, "groq:gpt-oss");
        when(extractor.extract(eq("a"), any(), any()))
                .thenReturn(new ConsultationExtractor.Extraction(model, null, false));
        when(extractor.extract(eq("a b"), any(), any()))
                .thenReturn(new ConsultationExtractor.Extraction(ConsultationDraft.empty("rules"),
                        ConsultationExtractor.FAILED, false));

        service.extract(c.getId(), "a");
        ExtractionResponse failed = service.extract(c.getId(), "a b");

        assertThat(failed.error()).isEqualTo(ConsultationExtractor.FAILED);
        assertThat(failed.draft().source()).isEqualTo("groq:gpt-oss");
        assertThat(failed.draft().treatmentPlan()).extracting(ConsultationDraft.PlanItem::label).containsExactly("Couronne");
        assertThat(failed.draft().chiefComplaint().value()).isEqualTo("Douleur");
        // A reload gets the model's reading back too, with the newer transcript.
        assertThat(repo.rows.get(c.getId()).getDraft()).contains("groq:gpt-oss").contains("Couronne");
        assertThat(repo.rows.get(c.getId()).getTranscript()).isEqualTo("a b");
    }

    @Test
    void aFailedReadingWithNoModelReadingYetShowsTheRules() {
        Consultation c = open(ConsultationStatus.INTAKE);
        when(extractor.extract(any(), any(), any()))
                .thenReturn(new ConsultationExtractor.Extraction(ConsultationDraft.empty("rules"),
                        ConsultationExtractor.FAILED, false));

        ExtractionResponse response = service.extract(c.getId(), "bonjour");

        assertThat(response.draft().source()).isEqualTo("rules");
    }

    @Test
    void theCatalogIsOfferedToTheExtractorActiveTreatmentsOnly() {
        Consultation c = open(ConsultationStatus.INTAKE);
        Treatment active = Treatment.builder().id(UUID.randomUUID()).code("DETART").name("Détartrage")
                .basePrice(new BigDecimal("250")).active(true).build();
        Treatment retired = Treatment.builder().id(UUID.randomUUID()).code("OLD").name("Ancien")
                .basePrice(BigDecimal.TEN).active(false).build();
        when(treatments.findAll()).thenReturn(List.of(retired, active));
        when(extractor.extract(any(), any(), any())).thenReturn(
                new ConsultationExtractor.Extraction(ConsultationDraft.empty("rules"), null, false));

        service.extract(c.getId(), "x");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.orthoflow.consultation.infrastructure.extraction.ConsultationPromptBuilder.CatalogEntry>> catalog =
                ArgumentCaptor.forClass(List.class);
        verify(extractor).extract(eq("x"), catalog.capture(), any());
        assertThat(catalog.getValue()).extracting("code").containsExactly("DETART");
    }

    @Test
    void aLateShorterTranscriptDoesNotOverwriteALongerOne() {
        assertThat(ConsultationService.longer("bonjour docteur", "bonjour")).isEqualTo("bonjour docteur");
        assertThat(ConsultationService.longer("bonjour", "bonjour docteur")).isEqualTo("bonjour docteur");
        // A different text is a correction, not a stale copy.
        assertThat(ConsultationService.longer("bonjour docteur", "salut")).isEqualTo("salut");
        assertThat(ConsultationService.longer(null, null)).isEmpty();
    }

    @Test
    void aReadingThatFinishesAfterTheConsultationWasSavedWritesNothing() {
        Consultation c = open(ConsultationStatus.INTAKE);
        when(extractor.extract(any(), any(), any())).thenAnswer(invocation -> {
            c.setStatus(ConsultationStatus.COMPLETED); // saved while the model was thinking
            return new ConsultationExtractor.Extraction(ConsultationDraft.empty("rules"), null, false);
        });
        int before = repo.saves;

        service.extract(c.getId(), "late");

        assertThat(repo.saves).isEqualTo(before);
        assertThat(c.getTranscript()).isEmpty();
    }

    @Test
    void aClosedConsultationCannotBeRead() {
        Consultation c = open(ConsultationStatus.COMPLETED);

        assertThatThrownBy(() -> service.extract(c.getId(), "x")).isInstanceOf(ValidationException.class);
        verify(extractor, never()).extract(any(), any(), any());
    }

    // ── Phases ──────────────────────────────────────────────────────────

    @Test
    void callingTheConsultationMovesIntakeToExaminationOnceAndSayingItTwiceIsHarmless() {
        Consultation c = open(ConsultationStatus.INTAKE);

        service.beginExamination(c.getId());
        assertThat(c.getStatus()).isEqualTo(ConsultationStatus.EXAMINATION);
        assertThat(c.getExaminationStartedAt()).isNotNull();
        var firstStamp = c.getExaminationStartedAt();

        service.beginExamination(c.getId());
        assertThat(c.getExaminationStartedAt()).isEqualTo(firstStamp);
    }

    @Test
    void youCannotGoBackToTheExaminationFromReview() {
        Consultation c = open(ConsultationStatus.REVIEW);

        assertThatThrownBy(() -> service.beginExamination(c.getId())).isInstanceOf(ValidationException.class);
    }

    @Test
    void endingMovesToReviewKeepsTheTranscriptAndTakesTheDictatedSessionToReviewToo() {
        Consultation c = open(ConsultationStatus.EXAMINATION);

        ConsultationResponse ended = service.end(c.getId(), "toute la conversation");

        assertThat(ended.status()).isEqualTo("REVIEW");
        assertThat(c.getTranscript()).isEqualTo("toute la conversation");
        assertThat(c.getEndedAt()).isNotNull();
        ArgumentCaptor<CompleteVoiceSessionRequest> completion = ArgumentCaptor.forClass(CompleteVoiceSessionRequest.class);
        verify(voiceSessions).end(eq(VOICE_SESSION), completion.capture(), eq(ACTOR));
        assertThat(completion.getValue().getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(completion.getValue().isConfirmed()).isFalse();
    }

    @Test
    void endingDoesNotTouchADictatedSessionTheBrowserAlreadyMovedToReview() {
        Consultation c = open(ConsultationStatus.EXAMINATION);
        when(voiceSessions.get(VOICE_SESSION)).thenReturn(session("PENDING_REVIEW"));

        service.end(c.getId(), "x");

        verify(voiceSessions, never()).end(any(), any(), any());
    }

    @Test
    void endingTwiceKeepsTheLongerTranscript() {
        Consultation c = open(ConsultationStatus.EXAMINATION);
        service.end(c.getId(), "une conversation entière");

        service.end(c.getId(), "une conversation");

        assertThat(c.getTranscript()).isEqualTo("une conversation entière");
        assertThat(c.getStatus()).isEqualTo(ConsultationStatus.REVIEW);
    }

    // ── Throwing away ───────────────────────────────────────────────────

    @Test
    void discardingErasesWhatWasRecordedAndAbandonsTheDictatedSession() {
        Consultation c = open(ConsultationStatus.REVIEW);
        c.setTranscript("sensible");
        c.setDraft("{\"allergies\":[]}");
        when(voiceSessions.get(VOICE_SESSION)).thenReturn(session("PENDING_REVIEW"));

        service.abandon(c.getId());

        assertThat(c.getStatus()).isEqualTo(ConsultationStatus.ABANDONED);
        assertThat(c.getTranscript()).isNull();
        assertThat(c.getDraft()).isNull();
        ArgumentCaptor<CompleteVoiceSessionRequest> completion = ArgumentCaptor.forClass(CompleteVoiceSessionRequest.class);
        verify(voiceSessions).end(eq(VOICE_SESSION), completion.capture(), eq(ACTOR));
        assertThat(completion.getValue().getStatus()).isEqualTo("ABANDONED");
    }

    @Test
    void aConsultationWhoseSaveAlreadyWroteChartFindingsCannotBeDiscarded() {
        Consultation c = open(ConsultationStatus.REVIEW);
        c.setTranscript("tout");
        when(voiceAudit.forSession(VOICE_SESSION)).thenReturn(List.of(
                VoiceCommandAuditResponse.builder().id(UUID.randomUUID()).outcome("EXECUTED").build(),
                VoiceCommandAuditResponse.builder().id(UUID.randomUUID()).outcome("FAILED").build()));

        assertThatThrownBy(() -> service.abandon(c.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("1 chart finding");
        assertThat(repo.rows.get(c.getId()).getStatus()).isEqualTo(ConsultationStatus.REVIEW);
        assertThat(repo.rows.get(c.getId()).getTranscript()).isEqualTo("tout");
        verify(voiceSessions, never()).end(any(), any(), any());
    }

    @Test
    void theDataExportHasEveryConsultationWithItsTranscriptEvenWithTheFeatureOff() {
        Consultation c = open(ConsultationStatus.EXAMINATION);
        c.setTranscript("ce qui a été dit");
        properties.setEnabled(false);

        List<ConsultationResponse> exported = service.forExport(PATIENT);

        assertThat(exported).extracting(ConsultationResponse::transcript).containsExactly("ce qui a été dit");
    }

    // ── Review state ────────────────────────────────────────────────────

    @Test
    void theDoctorsReviewIsKeptSoAReloadGetsItBack() throws Exception {
        Consultation c = open(ConsultationStatus.REVIEW);
        var state = new ObjectMapper().readTree("{\"v\":1,\"review\":{\"allergies\":[{\"key\":\"allergy:latex\",\"status\":\"removed\"}]}}");

        service.saveReviewState(c.getId(), state);

        ConsultationResponse reloaded = service.get(c.getId());
        assertThat(reloaded.reviewState().path("review").path("allergies").get(0).path("status").asText())
                .isEqualTo("removed");
        // the list a patient's history shows does not carry it
        assertThat(service.listForPatient(PATIENT).get(0).reviewState()).isNull();
    }

    @Test
    void aClosedConsultationTakesNoReviewState() throws Exception {
        Consultation c = open(ConsultationStatus.COMPLETED);

        assertThatThrownBy(() -> service.saveReviewState(c.getId(), new ObjectMapper().readTree("{}")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.saveReviewState(open(ConsultationStatus.REVIEW).getId(),
                new ObjectMapper().readTree("[1]"))).isInstanceOf(ValidationException.class);
    }

    @Test
    void discardingErasesTheReviewStateToo() throws Exception {
        Consultation c = open(ConsultationStatus.REVIEW);
        service.saveReviewState(c.getId(), new ObjectMapper().readTree("{\"v\":1}"));

        service.abandon(c.getId());

        assertThat(repo.rows.get(c.getId()).getReviewState()).isNull();
    }

    // ── Idle consultations ──────────────────────────────────────────────

    @Test
    void anIdleConsultationIsDiscardedEvenWithTheFeatureOff() {
        Consultation c = open(ConsultationStatus.EXAMINATION);
        c.setTranscript("tout ce qui a été dit");
        properties.setEnabled(false);

        assertThat(service.discardIdle(c.getId())).isTrue();

        assertThat(repo.rows.get(c.getId()).getStatus()).isEqualTo(ConsultationStatus.ABANDONED);
        assertThat(repo.rows.get(c.getId()).getTranscript()).isNull();
    }

    @Test
    void anIdleConsultationWhoseSaveWroteChartFindingsLosesItsTextButStaysForTheDoctorToSave() {
        Consultation c = open(ConsultationStatus.REVIEW);
        c.setTranscript("tout");
        c.setDraft("{}");
        c.setReviewState("{}");
        when(voiceAudit.forSession(VOICE_SESSION)).thenReturn(List.of(
                VoiceCommandAuditResponse.builder().id(UUID.randomUUID()).outcome("EXECUTED").build()));

        assertThat(service.discardIdle(c.getId())).isFalse();

        Consultation after = repo.rows.get(c.getId());
        assertThat(after.getStatus()).isEqualTo(ConsultationStatus.REVIEW);
        assertThat(after.getTranscript()).isNull();
        assertThat(after.getDraft()).isNull();
        assertThat(after.getReviewState()).isNull();
    }

    @Test
    void aSavedConsultationCannotBeDiscarded() {
        Consultation c = open(ConsultationStatus.COMPLETED);

        assertThatThrownBy(() -> service.abandon(c.getId())).isInstanceOf(ValidationException.class);
    }

    @Test
    void discardingDoesNotReopenADictatedSessionThatWasAlreadySaved() {
        Consultation c = open(ConsultationStatus.REVIEW);
        when(voiceSessions.get(VOICE_SESSION)).thenReturn(session("COMPLETED"));

        service.abandon(c.getId());

        verify(voiceSessions, never()).end(any(), any(), any());
    }

    // ── Reads ───────────────────────────────────────────────────────────

    @Test
    void aPatientsHistoryOmitsTranscriptsAndDiscardedConsultations() {
        Consultation kept = open(ConsultationStatus.COMPLETED);
        kept.setTranscript("secret");
        open(ConsultationStatus.ABANDONED);

        List<ConsultationResponse> list = service.listForPatient(PATIENT);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).transcript()).isNull();
        assertThat(service.get(kept.getId()).transcript()).isEqualTo("secret");
    }

    @Test
    void theUnfinishedConsultationIsOfferedBack() {
        assertThat(service.findOpen(PATIENT)).isNull();
        Consultation c = open(ConsultationStatus.REVIEW);

        assertThat(service.findOpen(PATIENT).id()).isEqualTo(c.getId());
    }
}
