package com.orthoflow.voice.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.domain.repository.PatientRepository;
import com.orthoflow.voice.application.dto.CommitVoiceSessionRequest;
import com.orthoflow.voice.application.dto.CommitVoiceSessionResponse;
import com.orthoflow.voice.domain.model.CommandOutcome;
import com.orthoflow.voice.domain.model.ConfirmationStatus;
import com.orthoflow.voice.domain.model.ResolverKind;
import com.orthoflow.voice.domain.model.RiskTier;
import com.orthoflow.voice.domain.model.VoiceCommandAudit;
import com.orthoflow.voice.domain.model.VoiceSession;
import com.orthoflow.voice.domain.model.VoiceSessionStatus;
import com.orthoflow.voice.domain.repository.VoiceCommandAuditRepository;
import com.orthoflow.voice.domain.repository.VoiceSessionRepository;
import com.orthoflow.voice.infrastructure.nlu.VoiceNluProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What "saved" means when a consultation is committed more than once.
 *
 * <p>The audit trail and the session are real services over in-memory stores,
 * so the states a command moves through are the ones production moves it
 * through; only the clinical write itself is replaced, by something that can
 * be told to fail. The cases are the ones a dentist reaches by pressing Save
 * again: after a failure, after fixing it, after removing it, twice at once.
 */
class VoiceSessionCommitServiceTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();

    private final List<String> events = new ArrayList<>();
    private final Set<UUID> failing = new HashSet<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();

    private InMemoryAudits audits;
    private InMemorySessions sessions;
    private VoiceAuditService auditService;
    private VoiceSessionCommitService service;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        audits = new InMemoryAudits();
        sessions = new InMemorySessions(events);
        auditService = new VoiceAuditService(audits, new VoiceNluProperties());
        VoiceSessionService sessionService = new VoiceSessionService(sessions, mock(PatientRepository.class));

        VoiceCommandService commands = mock(VoiceCommandService.class);
        when(commands.confirm(any(), any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            events.add("confirm " + id);
            attempts.merge(id, 1, Integer::sum);
            return failing.contains(id)
                    ? auditService.markFailed(id, "Finding code no longer accepted")
                    : auditService.markExecuted(id, "ToothFinding", UUID.randomUUID().toString(), null, "16: caries");
        });
        when(commands.record(any(), any())).thenAnswer(invocation ->
                auditService.record(invocation.getArgument(0), invocation.getArgument(1)));

        service = new VoiceSessionCommitService(sessions, audits, commands, auditService, sessionService);

        VoiceSession session = VoiceSession.builder()
                .id(UUID.randomUUID())
                .version(0L)
                .patientId(PATIENT)
                .actorId(ACTOR)
                .status(VoiceSessionStatus.PENDING_REVIEW)
                .startedAt(OffsetDateTime.now())
                .build();
        sessions.store.put(session.getId(), session);
        sessionId = session.getId();
    }

    private UUID stagedFinding() {
        return audit(ConfirmationStatus.PENDING, CommandOutcome.CLARIFICATION);
    }

    private UUID audit(ConfirmationStatus status, CommandOutcome outcome) {
        VoiceCommandAudit entry = VoiceCommandAudit.builder()
                .id(UUID.randomUUID())
                .actorId(ACTOR)
                .patientId(PATIENT)
                .sessionId(sessionId)
                .occurredAt(OffsetDateTime.now())
                .intent("clinical.addFindings")
                .entities("{\"fdi\":\"16\",\"findings\":[{\"code\":\"caries\"}]}")
                .resolver(ResolverKind.grammar)
                .riskTier(RiskTier.CONFIRM)
                .confirmationStatus(status)
                .outcome(outcome)
                .build();
        audits.save(entry);
        return entry.getId();
    }

    private CommitVoiceSessionResponse commit(List<UUID> approved, List<UUID> rejected) {
        CommitVoiceSessionRequest request = new CommitVoiceSessionRequest();
        request.setApprovedAuditIds(approved);
        request.setRejectedAuditIds(rejected);
        request.setSummary("Compte rendu");
        return service.commit(sessionId, request, ACTOR);
    }

    private VoiceCommandAudit row(UUID id) {
        return audits.findById(id).orElseThrow();
    }

    // ── A failure is not forgotten by the next Save ─────────────────────

    @Test
    void savingAgainWithoutFixingAFailureDoesNotCompleteTheSession() {
        UUID good = stagedFinding();
        UUID bad = stagedFinding();
        failing.add(bad);

        CommitVoiceSessionResponse first = commit(List.of(good, bad), List.of());
        assertThat(first.failed()).extracting(CommitVoiceSessionResponse.FailedCommand::auditId).containsExactly(bad);
        assertThat(first.session().status()).isEqualTo("PENDING_REVIEW");

        // The second Save used to skip `bad` (no longer pending), report no
        // failures, and mark the consultation complete with the finding lost.
        CommitVoiceSessionResponse second = commit(List.of(good, bad), List.of());

        assertThat(second.failed()).extracting(CommitVoiceSessionResponse.FailedCommand::auditId).containsExactly(bad);
        assertThat(second.session().status()).isEqualTo("PENDING_REVIEW");
        assertThat(second.session().confirmedAt()).isNull();
    }

    @Test
    void savingAgainDoesNotWriteWhatAlreadyLanded() {
        UUID good = stagedFinding();
        UUID bad = stagedFinding();
        failing.add(bad);

        commit(List.of(good, bad), List.of());
        CommitVoiceSessionResponse second = commit(List.of(good, bad), List.of());

        assertThat(attempts.get(good)).isEqualTo(1);
        assertThat(second.executed()).isZero();
    }

    @Test
    void aFailedCommandIsRetriedAndTheSessionCompletesOnceItLands() {
        UUID good = stagedFinding();
        UUID bad = stagedFinding();
        failing.add(bad);
        commit(List.of(good, bad), List.of());

        failing.clear();
        CommitVoiceSessionResponse second = commit(List.of(good, bad), List.of());

        assertThat(second.failed()).isEmpty();
        assertThat(second.executed()).isEqualTo(1);
        assertThat(second.session().status()).isEqualTo("COMPLETED");
        assertThat(row(bad).getOutcome()).isEqualTo(CommandOutcome.EXECUTED);
        // The failure stays in the trail as history.
        assertThat(row(bad).getErrorMessage()).startsWith("Retried after: Finding code no longer accepted");
    }

    @Test
    void removingAFailedCommandAtReviewLetsTheConsultationComplete() {
        UUID good = stagedFinding();
        UUID bad = stagedFinding();
        failing.add(bad);
        commit(List.of(good, bad), List.of());

        CommitVoiceSessionResponse second = commit(List.of(good), List.of(bad));

        assertThat(second.failed()).isEmpty();
        assertThat(second.session().status()).isEqualTo("COMPLETED");
        // Dismissed, not rewritten: the trail still says it was attempted and failed.
        assertThat(row(bad).getConfirmationStatus()).isEqualTo(ConfirmationStatus.CANCELLED);
        assertThat(row(bad).getOutcome()).isEqualTo(CommandOutcome.FAILED);
        assertThat(row(bad).getErrorMessage()).isEqualTo("Finding code no longer accepted");
        assertThat(attempts.get(bad)).isEqualTo(1);
    }

    @Test
    void anApprovedCommandInAnUnexpectedStateBlocksCompletion() {
        UUID stuck = audit(ConfirmationStatus.CONFIRMED, CommandOutcome.CLARIFICATION);

        CommitVoiceSessionResponse result = commit(List.of(stuck), List.of());

        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).errorMessage()).contains("unexpected state");
        assertThat(result.session().status()).isEqualTo("PENDING_REVIEW");
        assertThat(attempts).doesNotContainKey(stuck);
    }

    @Test
    void aCommandTheDentistTookBackIsNotResurrectedByApproval() {
        UUID taken = audit(ConfirmationStatus.REJECTED, CommandOutcome.REJECTED);

        CommitVoiceSessionResponse result = commit(List.of(taken), List.of());

        assertThat(result.failed()).isEmpty();
        assertThat(attempts).doesNotContainKey(taken);
        assertThat(result.session().status()).isEqualTo("COMPLETED");
    }

    @Test
    void aCommandAlreadyInTheRecordIsNeitherRunNorCountedAgain() {
        UUID done = audit(ConfirmationStatus.CONFIRMED, CommandOutcome.EXECUTED);

        CommitVoiceSessionResponse result = commit(List.of(done), List.of());

        assertThat(result.executed()).isZero();
        assertThat(result.failed()).isEmpty();
        assertThat(attempts).doesNotContainKey(done);
        assertThat(result.session().status()).isEqualTo("COMPLETED");
    }

    // ── Two commits of the same consultation ────────────────────────────

    @Test
    void theSessionIsTakenBeforeAnyCommandRuns() {
        UUID finding = stagedFinding();

        commit(List.of(finding), List.of());

        // Saving the session bumps its version; that first save is the claim.
        assertThat(events.get(0)).isEqualTo("save session");
        assertThat(events.indexOf("confirm " + finding)).isGreaterThan(0);
    }

    @Test
    void aCommitThatLosesTheClaimRunsNothing() {
        UUID finding = stagedFinding();
        sessions.failNextSave = true;

        assertThatThrownBy(() -> commit(List.of(finding), List.of()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(attempts).isEmpty();
        assertThat(row(finding).getConfirmationStatus()).isEqualTo(ConfirmationStatus.PENDING);
    }

    @Test
    void aSecondCommitReadingTheSameVersionIsRefused() {
        UUID finding = stagedFinding();
        // Both requests read version 0 before either saved.
        VoiceSession staleCopy = sessions.findById(sessionId).orElseThrow();

        commit(List.of(finding), List.of());

        assertThatThrownBy(() -> sessions.save(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ── Sessions that cannot be saved ───────────────────────────────────

    @Test
    void aSavedConsultationCannotBeSavedAgain() {
        UUID finding = stagedFinding();
        commit(List.of(finding), List.of());

        assertThatThrownBy(() -> commit(List.of(finding), List.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already been saved");
    }

    @Test
    void aDiscardedConsultationCannotBeSaved() {
        VoiceSession session = sessions.store.get(sessionId);
        session.setStatus(VoiceSessionStatus.ABANDONED);
        UUID finding = stagedFinding();

        assertThatThrownBy(() -> commit(List.of(finding), List.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("discarded");
        assertThat(attempts).isEmpty();
    }

    @Test
    void aCommandFromAnotherConsultationCannotBeApprovedHere() {
        UUID foreign = stagedFinding();
        audits.findById(foreign).orElseThrow().setSessionId(UUID.randomUUID());

        assertThatThrownBy(() -> commit(List.of(foreign), List.of()))
                .isInstanceOf(ValidationException.class);
        assertThat(attempts).isEmpty();
    }

    // ── In-memory stores ────────────────────────────────────────────────

    private static final class InMemoryAudits implements VoiceCommandAuditRepository {
        final Map<UUID, VoiceCommandAudit> store = new HashMap<>();

        @Override
        public VoiceCommandAudit save(VoiceCommandAudit entry) {
            if (entry.getId() == null) entry.prePersist();
            store.put(entry.getId(), entry);
            return entry;
        }

        @Override
        public Optional<VoiceCommandAudit> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<VoiceCommandAudit> findByPatient(UUID patientId) {
            return store.values().stream().filter(a -> patientId.equals(a.getPatientId())).toList();
        }

        @Override
        public List<VoiceCommandAudit> findBySession(UUID sessionId) {
            return store.values().stream().filter(a -> sessionId.equals(a.getSessionId())).toList();
        }
    }

    /**
     * Behaves as JPA does for a versioned entity: reads return a copy, and a
     * write made from a copy whose version is no longer current is refused.
     */
    private static final class InMemorySessions implements VoiceSessionRepository {
        final Map<UUID, VoiceSession> store = new HashMap<>();
        final List<String> events;
        boolean failNextSave;

        InMemorySessions(List<String> events) {
            this.events = events;
        }

        @Override
        public VoiceSession save(VoiceSession session) {
            events.add("save session");
            if (failNextSave) {
                failNextSave = false;
                throw new ObjectOptimisticLockingFailureException(VoiceSession.class, session.getId());
            }
            VoiceSession current = store.get(session.getId());
            if (current != null && !current.getVersion().equals(session.getVersion())) {
                throw new ObjectOptimisticLockingFailureException(VoiceSession.class, session.getId());
            }
            VoiceSession saved = copy(session);
            saved.setVersion(session.getVersion() + 1);
            store.put(saved.getId(), saved);
            return copy(saved);
        }

        @Override
        public Optional<VoiceSession> findById(UUID id) {
            return Optional.ofNullable(store.get(id)).map(InMemorySessions::copy);
        }

        @Override
        public List<VoiceSession> findByPatient(UUID patientId) {
            return store.values().stream().filter(s -> patientId.equals(s.getPatientId())).map(InMemorySessions::copy).toList();
        }

        private static VoiceSession copy(VoiceSession s) {
            return VoiceSession.builder()
                    .id(s.getId()).version(s.getVersion()).patientId(s.getPatientId()).actorId(s.getActorId())
                    .status(s.getStatus()).locale(s.getLocale()).summary(s.getSummary())
                    .startedAt(s.getStartedAt()).endedAt(s.getEndedAt()).confirmedAt(s.getConfirmedAt())
                    .build();
        }
    }
}
