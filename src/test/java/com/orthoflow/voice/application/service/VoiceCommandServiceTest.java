package com.orthoflow.voice.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.clinical.application.dto.ClinicalNoteResponse;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.tasks.application.dto.TaskDtos;
import com.orthoflow.tasks.application.service.TaskService;
import com.orthoflow.tasks.domain.model.Task;
import com.orthoflow.voice.application.dto.RecordVoiceCommandRequest;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import com.orthoflow.voice.domain.model.CommandOutcome;
import com.orthoflow.voice.domain.model.ConfirmationStatus;
import com.orthoflow.voice.domain.model.ResolverKind;
import com.orthoflow.voice.domain.model.RiskTier;
import com.orthoflow.voice.domain.model.VoiceCommandAudit;
import com.orthoflow.voice.domain.repository.VoiceCommandAuditRepository;
import com.orthoflow.voice.infrastructure.nlu.VoiceNluProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Who decides what a voice command is, and that a command is written once.
 *
 * <p>The audit trail is real (over an in-memory store with the same atomic
 * transition the database gives); the clinical write is a mock that counts.
 */
class VoiceCommandServiceTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID PRACTICE = UUID.randomUUID();

    private Audits audits;
    private VoiceAuditService auditService;
    private ClinicalRecordService clinical;
    private TaskService tasks;
    private CurrentUserProvider currentUser;
    private VoiceCommandService service;

    @BeforeEach
    void setUp() {
        audits = new Audits();
        auditService = new VoiceAuditService(audits, new VoiceNluProperties());
        clinical = mock(ClinicalRecordService.class);
        tasks = mock(TaskService.class);
        currentUser = mock(CurrentUserProvider.class);
        when(currentUser.requirePracticeId()).thenReturn(PRACTICE);
        service = new VoiceCommandService(audits, auditService, mock(VoiceInterpretationService.class),
                clinical, mock(VoiceSessionService.class), tasks, currentUser, new ObjectMapper());
    }

    private RecordVoiceCommandRequest request(String intent, String tier, String status, String outcome) {
        RecordVoiceCommandRequest r = new RecordVoiceCommandRequest();
        r.setPatientId(PATIENT);
        r.setSessionId(SESSION);
        r.setIntent(intent);
        r.setEntities("{}");
        r.setResolver("grammar");
        r.setRiskTier(tier);
        r.setConfirmationStatus(status);
        r.setOutcome(outcome);
        return r;
    }

    private UUID pendingNote() {
        VoiceCommandAuditResponse recorded = service.record(
                request("clinical.addNote", "CONFIRM", "PENDING", "CLARIFICATION"), ACTOR);
        audits.store.get(recorded.id()).setEntities("{\"content\":\"Patient anxieux\"}");
        ClinicalNoteResponse note = mock(ClinicalNoteResponse.class);
        when(note.id()).thenReturn(UUID.randomUUID());
        when(note.content()).thenReturn("Patient anxieux");
        when(clinical.addNote(any(), any(), any())).thenReturn(note);
        return recorded.id();
    }

    // ── A write is a write whatever the client says ─────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "clinical.addFinding", "clinical.addFindings", "clinical.retractFindings",
            "clinical.resolveFinding", "clinical.retractFinding", "clinical.addNote",
            "clinical.addAllergy", "clinical.addMedicalHistory"})
    void aClinicalWriteCannotBeLoggedAsSafeAndExecuted(String intent) {
        assertThatThrownBy(() -> service.record(request(intent, "SAFE", "AUTO", "EXECUTED"), ACTOR))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("CONFIRM");
        assertThat(audits.store).isEmpty();
    }

    @Test
    void aPendingWriteCannotArriveWithAnOutcomeAlreadyDecided() {
        for (String outcome : List.of("EXECUTED", "FAILED", "UNDONE", "REJECTED")) {
            assertThatThrownBy(() -> service.record(
                    request("clinical.addNote", "CONFIRM", "PENDING", outcome), ACTOR))
                    .isInstanceOf(ValidationException.class);
        }
        assertThat(audits.store).isEmpty();
    }

    @Test
    void aReadOrNavigationStillRecordsAsSafeAndExecuted() {
        VoiceCommandAuditResponse saved = service.record(
                request("chart.readTooth", "SAFE", "AUTO", "EXECUTED"), ACTOR);

        assertThat(saved.outcome()).isEqualTo("EXECUTED");
    }

    @Test
    void everyIntentTheServerTreatsAsAWriteIsOneTheExecutorKnows() {
        // WRITE_INTENTS must not drift from execute(): an intent listed there
        // but missing from the switch would fail on confirm with "Unknown
        // voice intent"; one in the switch but not listed would be a write a
        // client could declare SAFE.
        for (String intent : VoiceCommandService.WRITE_INTENTS) {
            VoiceCommandAudit row = pendingRow(intent, "{}");
            VoiceCommandAuditResponse result = service.confirm(row.getId(), ACTOR);

            assertThat(result.errorMessage()).as(intent).doesNotContain("Unknown voice intent");
        }
    }

    private VoiceCommandAudit pendingRow(String intent, String entities) {
        VoiceCommandAudit row = VoiceCommandAudit.builder()
                .id(UUID.randomUUID()).actorId(ACTOR).patientId(PATIENT).sessionId(SESSION)
                .occurredAt(OffsetDateTime.now()).intent(intent).entities(entities)
                .resolver(ResolverKind.grammar).riskTier(RiskTier.CONFIRM)
                .confirmationStatus(ConfirmationStatus.PENDING).outcome(CommandOutcome.CLARIFICATION)
                .build();
        audits.save(row);
        return row;
    }

    // ── A task spoken for the team ──────────────────────────────────────

    private TaskDtos.View created(UUID id, String title) {
        return new TaskDtos.View(id, title, null, null, null, UserRole.ASSISTANT, ACTOR, null, Task.Priority.NORMAL,
                PATIENT, null, Task.Status.OPEN, null, false, null, null);
    }

    @Test
    void aSpokenTaskIsCreatedForTheRoleNamedAndLinkedToThePatientItIsAbout() {
        UUID taskId = UUID.randomUUID();
        when(tasks.create(any(), any(), any())).thenReturn(created(taskId, "Rappeler le patient"));
        VoiceCommandAudit row = pendingRow("tasks.create",
                "{\"title\":\"Rappeler le patient\",\"assigneeRole\":\"assistant\",\"dueDate\":\"2026-10-09\",\"priority\":\"high\",\"linkPatient\":true}");

        VoiceCommandAuditResponse result = service.confirm(row.getId(), ACTOR);

        ArgumentCaptor<TaskDtos.Request> sent = ArgumentCaptor.forClass(TaskDtos.Request.class);
        verify(tasks).create(eq(PRACTICE), eq(ACTOR), sent.capture());
        assertThat(sent.getValue().title()).isEqualTo("Rappeler le patient");
        assertThat(sent.getValue().assigneeRole()).isEqualTo(UserRole.ASSISTANT);
        assertThat(sent.getValue().assigneeId()).isNull();
        assertThat(sent.getValue().dueDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(sent.getValue().priority()).isEqualTo(Task.Priority.HIGH);
        assertThat(sent.getValue().patientId()).isEqualTo(PATIENT);
        assertThat(result.outcome()).isEqualTo("EXECUTED");
        assertThat(audits.store.get(row.getId()).getTargetId()).isEqualTo(taskId.toString());
    }

    @Test
    void aSpokenTaskWithNoRoleIsTheDoctorsOwn() {
        when(tasks.create(any(), any(), any())).thenReturn(created(UUID.randomUUID(), "Commander des gants"));
        VoiceCommandAudit row = pendingRow("tasks.create", "{\"title\":\"Commander des gants\"}");

        service.confirm(row.getId(), ACTOR);

        ArgumentCaptor<TaskDtos.Request> sent = ArgumentCaptor.forClass(TaskDtos.Request.class);
        verify(tasks).create(any(), any(), sent.capture());
        assertThat(sent.getValue().assigneeRole()).isNull();
        assertThat(sent.getValue().dueDate()).isNull();
        assertThat(sent.getValue().priority()).isNull();
        // A patient happened to be open, but the task was not about them.
        assertThat(sent.getValue().patientId()).isNull();
    }

    @Test
    void speakingATaskNeedsTheTasksPermissionAtTheMomentOfWriting() {
        doThrow(new AccessDeniedException("Missing permission TASKS_MANAGE")).when(currentUser)
                .requireAuthority("TASKS_MANAGE");
        VoiceCommandAudit row = pendingRow("tasks.create", "{\"title\":\"Rappeler le patient\"}");

        VoiceCommandAuditResponse result = service.confirm(row.getId(), ACTOR);

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("TASKS_MANAGE");
        verify(tasks, times(0)).create(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"x\",\"assigneeRole\":\"janitor\"}",
            "{\"title\":\"x\",\"dueDate\":\"demain\"}",
            "{\"title\":\"x\",\"priority\":\"whenever\"}",
            "{\"assigneeRole\":\"assistant\"}"})
    void aSpokenTaskWithSomethingUnintelligibleCreatesNothing(String entities) {
        VoiceCommandAudit row = pendingRow("tasks.create", entities);

        VoiceCommandAuditResponse result = service.confirm(row.getId(), ACTOR);

        assertThat(result.outcome()).isEqualTo("FAILED");
        verify(tasks, times(0)).create(any(), any(), any());
    }

    @Test
    void aTaskCannotBeLoggedAsSafeAndExecuted() {
        assertThatThrownBy(() -> service.record(request("tasks.create", "SAFE", "AUTO", "EXECUTED"), ACTOR))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("CONFIRM");
    }

    // ── Written once ────────────────────────────────────────────────────

    @Test
    void confirmingTwiceWritesTheNoteOnce() {
        UUID id = pendingNote();

        service.confirm(id, ACTOR);
        assertThatThrownBy(() -> service.confirm(id, ACTOR)).isInstanceOf(ValidationException.class);

        verify(clinical, times(1)).addNote(any(), any(), any());
    }

    @Test
    void twoConfirmsThatBothSawPendingStillWriteOnce() {
        UUID id = pendingNote();

        service.confirm(id, ACTOR);
        // The second request read the row before the first claimed it.
        audits.staleReadsLeft = 1;
        assertThatThrownBy(() -> service.confirm(id, ACTOR)).isInstanceOf(ConflictException.class);

        verify(clinical, times(1)).addNote(any(), any(), any());
    }

    @Test
    void aConfirmThatLosesTheRaceToARejectWritesNothing() {
        UUID id = pendingNote();

        service.reject(id, ACTOR);
        audits.staleReadsLeft = 1;
        assertThatThrownBy(() -> service.confirm(id, ACTOR)).isInstanceOf(ConflictException.class);

        verify(clinical, times(0)).addNote(any(), any(), any());
        assertThat(audits.store.get(id).getConfirmationStatus()).isEqualTo(ConfirmationStatus.REJECTED);
    }

    @Test
    void aFailedWriteIsRecordedAsFailedAndNotLost() {
        UUID id = pendingNote();
        when(clinical.addNote(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        VoiceCommandAuditResponse result = service.confirm(id, ACTOR);

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).isEqualTo("db down");
        assertThat(audits.store.get(id).getConfirmationStatus()).isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    void aLateFailureNeverOverwritesAWriteThatLanded() {
        UUID id = pendingNote();
        service.confirm(id, ACTOR);

        VoiceCommandAuditResponse after = auditService.markFailed(id, "lost a race");

        assertThat(after.outcome()).isEqualTo("EXECUTED");
        assertThat(audits.store.get(id).getErrorMessage()).isNull();
    }

    /**
     * In-memory trail with the database's atomic transition, and the option to
     * hand a caller the row as it was before someone else's claim.
     */
    private static final class Audits implements VoiceCommandAuditRepository {
        final Map<UUID, VoiceCommandAudit> store = new HashMap<>();
        Map<UUID, ConfirmationStatus> staleStatus = new HashMap<>();
        int staleReadsLeft;

        @Override
        public VoiceCommandAudit save(VoiceCommandAudit entry) {
            if (entry.getId() == null) entry.prePersist();
            store.put(entry.getId(), entry);
            return entry;
        }

        @Override
        public Optional<VoiceCommandAudit> findById(UUID id) {
            VoiceCommandAudit entry = store.get(id);
            if (entry != null && staleReadsLeft > 0 && entry.getConfirmationStatus() != ConfirmationStatus.PENDING) {
                // A reader that loaded the row before the claim committed.
                staleReadsLeft--;
                VoiceCommandAudit earlier = VoiceCommandAudit.builder()
                        .id(entry.getId()).actorId(entry.getActorId()).patientId(entry.getPatientId())
                        .sessionId(entry.getSessionId()).occurredAt(entry.getOccurredAt())
                        .intent(entry.getIntent()).entities(entry.getEntities()).resolver(entry.getResolver())
                        .riskTier(entry.getRiskTier()).confirmationStatus(ConfirmationStatus.PENDING)
                        .outcome(CommandOutcome.CLARIFICATION).build();
                return Optional.of(earlier);
            }
            return Optional.ofNullable(entry);
        }

        @Override
        public List<VoiceCommandAudit> findByPatient(UUID patientId) {
            return store.values().stream().filter(a -> patientId.equals(a.getPatientId())).toList();
        }

        @Override
        public List<VoiceCommandAudit> findBySession(UUID sessionId) {
            return store.values().stream().filter(a -> sessionId.equals(a.getSessionId())).toList();
        }

        @Override
        public int scrubPatientData(UUID patientId) {
            return 0;
        }

        @Override
        public boolean transitionConfirmation(UUID id, ConfirmationStatus from, ConfirmationStatus to) {
            VoiceCommandAudit entry = store.get(id);
            if (entry == null || entry.getConfirmationStatus() != from) return false;
            entry.setConfirmationStatus(to);
            return true;
        }
    }
}
