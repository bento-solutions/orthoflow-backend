package com.orthoflow.consultation.application.service;

import static com.orthoflow.consultation.application.service.ConsultationFixtures.ACTOR;
import static com.orthoflow.consultation.application.service.ConsultationFixtures.PATIENT;
import static com.orthoflow.consultation.application.service.ConsultationFixtures.VOICE_SESSION;
import static com.orthoflow.consultation.application.service.ConsultationFixtures.consultation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.consultation.application.dto.CommitConsultationRequest;
import com.orthoflow.consultation.application.dto.CommitConsultationResponse;
import com.orthoflow.consultation.application.service.ConsultationFixtures.InMemoryConsultations;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.voice.application.dto.CommitVoiceSessionRequest;
import com.orthoflow.voice.application.dto.CommitVoiceSessionResponse;
import com.orthoflow.voice.application.service.VoiceSessionCommitService;
import com.orthoflow.voice.domain.model.VoiceSession;
import com.orthoflow.voice.domain.model.VoiceSessionStatus;
import com.orthoflow.voice.domain.repository.VoiceSessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * The order is the point: chart findings first (they can half-succeed), the
 * rest of the record second (it is atomic). Anything else lets a save say
 * "done" in some tables and not others.
 */
class ConsultationCommitServiceTest {

    private InMemoryConsultations repo;
    private ConsultationRecordWriter writer;
    private VoiceSessionCommitService voiceCommit;
    private VoiceSessionRepository voiceSessions;
    private ConsultationCommitService service;
    private Consultation consultation;

    @BeforeEach
    void setUp() {
        repo = new InMemoryConsultations();
        writer = mock(ConsultationRecordWriter.class);
        voiceCommit = mock(VoiceSessionCommitService.class);
        voiceSessions = mock(VoiceSessionRepository.class);
        ConsultationExtractionProperties properties = new ConsultationExtractionProperties();
        properties.setEnabled(true);
        ConsultationService consultationService = new ConsultationService(repo, null, null, null, null, null, properties,
                new ObjectMapper());
        service = new ConsultationCommitService(repo, consultationService, writer, voiceCommit, voiceSessions);

        consultation = repo.save(consultation(ConsultationStatus.REVIEW));
        VoiceSession session = VoiceSession.builder().id(VOICE_SESSION).patientId(PATIENT).actorId(ACTOR)
                .status(VoiceSessionStatus.PENDING_REVIEW).build();
        when(voiceSessions.findById(VOICE_SESSION)).thenReturn(Optional.of(session));
        when(writer.write(any(), any(), any())).thenAnswer(invocation -> {
            consultation.setStatus(ConsultationStatus.COMPLETED);
            return consultation;
        });
    }

    private static CommitConsultationRequest request() {
        CommitConsultationRequest request = new CommitConsultationRequest();
        request.setReport("Compte rendu.");
        request.setApprovedAuditIds(List.of(UUID.randomUUID()));
        request.setRejectedAuditIds(List.of(UUID.randomUUID()));
        return request;
    }

    private static CommitVoiceSessionResponse chart(int executed, List<CommitVoiceSessionResponse.FailedCommand> failed) {
        return CommitVoiceSessionResponse.builder().executed(executed).failed(failed).build();
    }

    @Test
    void savesTheChartFindingsThenTheRestAndReportsSaved() {
        when(voiceCommit.commit(eq(VOICE_SESSION), any(), eq(ACTOR))).thenReturn(chart(3, List.of()));
        CommitConsultationRequest request = request();

        CommitConsultationResponse response = service.commit(consultation.getId(), request, ACTOR);

        assertThat(response.saved()).isTrue();
        assertThat(response.executed()).isEqualTo(3);
        InOrder order = inOrder(voiceCommit, writer);
        order.verify(voiceCommit).commit(eq(VOICE_SESSION), any(), eq(ACTOR));
        order.verify(writer).write(eq(consultation.getId()), eq(request), eq(ACTOR));
    }

    @Test
    void handsTheChartMachineryExactlyWhatTheDoctorDecidedAndTheReportAsItsSummary() {
        when(voiceCommit.commit(any(), any(), any())).thenReturn(chart(1, List.of()));
        CommitConsultationRequest request = request();

        service.commit(consultation.getId(), request, ACTOR);

        ArgumentCaptor<CommitVoiceSessionRequest> sent = ArgumentCaptor.forClass(CommitVoiceSessionRequest.class);
        verify(voiceCommit).commit(eq(VOICE_SESSION), sent.capture(), eq(ACTOR));
        assertThat(sent.getValue().getApprovedAuditIds()).isEqualTo(request.getApprovedAuditIds());
        assertThat(sent.getValue().getRejectedAuditIds()).isEqualTo(request.getRejectedAuditIds());
        assertThat(sent.getValue().getSummary()).isEqualTo("Compte rendu.");
    }

    @Test
    void aFindingThatFailsToWriteLeavesTheWholeConsultationInReviewAndWritesNothingElse() {
        var failure = CommitVoiceSessionResponse.FailedCommand.builder()
                .auditId(UUID.randomUUID()).intent("clinical.addFindings").errorMessage("unknown finding").build();
        when(voiceCommit.commit(any(), any(), any())).thenReturn(chart(2, List.of(failure)));

        CommitConsultationResponse response = service.commit(consultation.getId(), request(), ACTOR);

        assertThat(response.saved()).isFalse();
        assertThat(response.failed()).containsExactly(failure);
        assertThat(consultation.getStatus()).isEqualTo(ConsultationStatus.REVIEW);
        verify(writer, never()).write(any(), any(), any());
    }

    @Test
    void aRetryAfterTheChartWasSavedButTheRestFailedDoesNotRunTheChartAgain() {
        VoiceSession done = VoiceSession.builder().id(VOICE_SESSION).patientId(PATIENT).actorId(ACTOR)
                .status(VoiceSessionStatus.COMPLETED).build();
        when(voiceSessions.findById(VOICE_SESSION)).thenReturn(Optional.of(done));

        CommitConsultationResponse response = service.commit(consultation.getId(), request(), ACTOR);

        assertThat(response.saved()).isTrue();
        verify(voiceCommit, never()).commit(any(), any(), any());
        verify(writer).write(any(), any(), any());
    }

    @Test
    void aConsultationWithNoDictatedSessionStillSaves() {
        consultation.setVoiceSessionId(null);

        assertThat(service.commit(consultation.getId(), request(), ACTOR).saved()).isTrue();
        verify(voiceCommit, never()).commit(any(), any(), any());
    }

    @Test
    void takesTheRowBeforeAnyClinicalWriteSoASecondSaveIsTurnedAway() {
        when(voiceCommit.commit(any(), any(), any())).thenAnswer(invocation -> {
            // By the time the chart is being written the row has already been saved with a stamp.
            assertThat(consultation.getSaveStartedAt()).isNotNull();
            assertThat(repo.saves).isGreaterThanOrEqualTo(2);
            return chart(0, List.of());
        });

        service.commit(consultation.getId(), request(), ACTOR);

        verify(voiceCommit).commit(any(), any(), any());
    }

    @Test
    void refusesASavedDiscardedOrStillRecordingConsultation() {
        for (ConsultationStatus status : List.of(ConsultationStatus.COMPLETED, ConsultationStatus.ABANDONED,
                ConsultationStatus.INTAKE, ConsultationStatus.EXAMINATION)) {
            consultation.setStatus(status);
            assertThatThrownBy(() -> service.commit(consultation.getId(), request(), ACTOR))
                    .as(status.name()).isInstanceOf(ValidationException.class);
        }
        verify(writer, never()).write(any(), any(), any());
    }
}
