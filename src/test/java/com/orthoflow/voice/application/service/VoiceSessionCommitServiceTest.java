package com.orthoflow.voice.application.service;

import com.orthoflow.clinical.application.service.ClinicalRecordService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    /** Makes the command a correction recorded fail when it is run. */
    private boolean failAnyReplacement;

    private InMemoryAudits audits;
    private InMemorySessions sessions;
    private VoiceAuditService auditService;
    private VoiceSessionCommitService service;
    private ClinicalRecordService clinical;
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
            boolean replacement = audits.store.get(id).getResolver() == ResolverKind.manual;
            return failing.contains(id) || (failAnyReplacement && replacement)
                    ? auditService.markFailed(id, "Finding code no longer accepted")
                    : auditService.markExecuted(id, "ToothFinding", UUID.randomUUID().toString(), null, "16: caries");
        });
        when(commands.record(any(), any())).thenAnswer(invocation ->
                auditService.record(invocation.getArgument(0), invocation.getArgument(1)));

        clinical = mock(ClinicalRecordService.class);
        service = new VoiceSessionCommitService(sessions, audits, commands, auditService, sessionService, clinical);

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

    // ── Correcting a tooth at review ────────────────────────────────────

    private CommitVoiceSessionRequest.Amendment amendmentOf(UUID original, String fdi) {
        CommitVoiceSessionRequest.Amendment amendment = new CommitVoiceSessionRequest.Amendment();
        amendment.setOriginalAuditId(original);
        amendment.setIntent("clinical.addFindings");
        amendment.setEntities("{\"fdi\":\"" + fdi + "\",\"findings\":[{\"code\":\"caries\"}]}");
        return amendment;
    }

    private CommitVoiceSessionResponse commitWith(List<UUID> approved, List<UUID> rejected,
                                                   CommitVoiceSessionRequest.Amendment... amendments) {
        CommitVoiceSessionRequest request = new CommitVoiceSessionRequest();
        request.setApprovedAuditIds(approved);
        request.setRejectedAuditIds(rejected);
        request.setAmendments(List.of(amendments));
        request.setSummary("Compte rendu");
        return service.commit(sessionId, request, ACTOR);
    }

    /** The command a correction recorded: the only one made by hand (resolver manual). */
    private VoiceCommandAudit replacementOf(UUID original) {
        return audits.store.values().stream()
                .filter(a -> a.getResolver() == ResolverKind.manual)
                .findFirst().orElseThrow();
    }

    @Test
    void aCorrectedTeethIsSavedAsTheCorrectionAndTheOriginalIsNot() {
        UUID original = stagedFinding();

        CommitVoiceSessionResponse result = commitWith(List.of(), List.of(), amendmentOf(original, "26"));

        // The original was taken for an amendment already applied and skipped,
        // so the finding was rejected and never replaced: lost without a word.
        VoiceCommandAudit replacement = replacementOf(original);
        assertThat(result.amended()).isEqualTo(1);
        assertThat(result.executed()).isEqualTo(1);
        assertThat(replacement.getOutcome()).isEqualTo(CommandOutcome.EXECUTED);
        assertThat(replacement.getEntities()).contains("\"fdi\":\"26\"");
        assertThat(replacement.getResolver()).isEqualTo(ResolverKind.manual);
        assertThat(row(original).getConfirmationStatus()).isEqualTo(ConfirmationStatus.REJECTED);
        assertThat(attempts).doesNotContainKey(original);
        assertThat(result.session().status()).isEqualTo("COMPLETED");
    }

    @Test
    void theOriginalIsNotWrittenEvenIfItIsAlsoApproved() {
        UUID original = stagedFinding();

        commitWith(List.of(original), List.of(), amendmentOf(original, "26"));

        assertThat(attempts).doesNotContainKey(original);
    }

    @Test
    void aCorrectionThatFailsKeepsTheExaminationOpenAndNamesTheReplacement() {
        UUID original = stagedFinding();
        failAnyReplacement = true;

        CommitVoiceSessionResponse result = commitWith(List.of(), List.of(), amendmentOf(original, "26"));

        UUID replacement = replacementOf(original).getId();
        assertThat(result.session().status()).isEqualTo("PENDING_REVIEW");
        assertThat(result.failed()).extracting(CommitVoiceSessionResponse.FailedCommand::auditId)
                .containsExactly(replacement);
    }

    @Test
    void savingAgainRetriesTheReplacementWithoutMakingAnother() {
        UUID original = stagedFinding();
        failAnyReplacement = true;
        commitWith(List.of(), List.of(), amendmentOf(original, "26"));
        UUID replacement = replacementOf(original).getId();

        failAnyReplacement = false;
        // The page sends the correction again, and approves the replacement it was told about.
        CommitVoiceSessionResponse second = commitWith(List.of(replacement), List.of(), amendmentOf(original, "26"));

        assertThat(second.session().status()).isEqualTo("COMPLETED");
        assertThat(second.amended()).isZero();
        assertThat(audits.store.values().stream().filter(a -> a.getResolver() == ResolverKind.manual)).hasSize(1);
        assertThat(row(replacement).getOutcome()).isEqualTo(CommandOutcome.EXECUTED);
    }

    @Test
    void aFailedReplacementTheReloadedPageNeverSawStillBlocksCompletion() {
        UUID original = stagedFinding();
        failAnyReplacement = true;
        commitWith(List.of(), List.of(), amendmentOf(original, "26"));
        UUID replacement = replacementOf(original).getId();

        // A reloaded page knows nothing of the replacement and lists only what it has.
        CommitVoiceSessionResponse second = commitWith(List.of(), List.of());

        assertThat(second.session().status()).isEqualTo("PENDING_REVIEW");
        assertThat(second.failed()).extracting(CommitVoiceSessionResponse.FailedCommand::auditId)
                .containsExactly(replacement);
    }

    @Test
    void aCorrectionCannotReachAnotherExaminationsCommand() {
        UUID foreign = UUID.randomUUID();
        audits.save(VoiceCommandAudit.builder()
                .id(foreign).actorId(ACTOR).patientId(PATIENT).sessionId(UUID.randomUUID())
                .occurredAt(OffsetDateTime.now()).intent("clinical.addFindings").entities("{}")
                .resolver(ResolverKind.grammar).riskTier(RiskTier.CONFIRM)
                .confirmationStatus(ConfirmationStatus.PENDING).outcome(CommandOutcome.CLARIFICATION).build());

        assertThatThrownBy(() -> commitWith(List.of(), List.of(), amendmentOf(foreign, "26")))
                .isInstanceOf(ValidationException.class);
    }

    // ── Commands that cannot be run again ───────────────────────────────

    @Test
    void aCommandStartedAndNeverFinishedBlocksCompletionUntilItIsRemoved() {
        UUID good = stagedFinding();
        UUID stuck = audit(ConfirmationStatus.CONFIRMED, CommandOutcome.CLARIFICATION);

        CommitVoiceSessionResponse first = commit(List.of(good), List.of());

        assertThat(first.session().status()).isEqualTo("PENDING_REVIEW");
        assertThat(first.failed()).extracting(CommitVoiceSessionResponse.FailedCommand::auditId).containsExactly(stuck);
        assertThat(first.failed().get(0).errorMessage()).contains("never finished");

        // Removing it from the list the page cannot otherwise correct lets the
        // examination complete, and the trail keeps what happened to it.
        CommitVoiceSessionResponse second = commit(List.of(good), List.of(stuck));

        assertThat(second.failed()).isEmpty();
        assertThat(second.session().status()).isEqualTo("COMPLETED");
        assertThat(row(stuck).getConfirmationStatus()).isEqualTo(ConfirmationStatus.CANCELLED);
        assertThat(row(stuck).getOutcome()).isEqualTo(CommandOutcome.FAILED);
        assertThat(row(stuck).getErrorMessage()).contains("Never finished");
    }

    @Test
    void aFailedReplacementCanBeRemovedWithoutTheOriginalOrAnyEntry() {
        UUID original = stagedFinding();
        failAnyReplacement = true;
        commitWith(List.of(), List.of(), amendmentOf(original, "26"));
        UUID replacement = replacementOf(original).getId();

        CommitVoiceSessionResponse second = commitWith(List.of(), List.of(replacement));

        assertThat(second.session().status()).isEqualTo("COMPLETED");
        assertThat(row(replacement).getConfirmationStatus()).isEqualTo(ConfirmationStatus.CANCELLED);
        assertThat(row(replacement).getOutcome()).isEqualTo(CommandOutcome.FAILED);
    }

    // ── What the review page never showed ───────────────────────────────

    @Test
    void aDictatedCommandOnNeitherListIsCancelledNotLeftToBeConfirmedLater() {
        UUID shown = stagedFinding();
        UUID unlisted = stagedFinding();

        CommitVoiceSessionResponse result = commit(List.of(shown), List.of());

        assertThat(result.session().status()).isEqualTo("COMPLETED");
        assertThat(result.notReviewed()).isEqualTo(1);
        // Not written, and no longer pending: a later call to /confirm cannot
        // put it into the chart under a consultation that is already closed.
        assertThat(attempts).doesNotContainKey(unlisted);
        assertThat(row(unlisted).getConfirmationStatus()).isEqualTo(ConfirmationStatus.CANCELLED);
        assertThat(row(unlisted).getOutcome()).isEqualTo(CommandOutcome.REJECTED);
        assertThat(row(unlisted).getErrorMessage()).startsWith("Not reviewed");
    }

    @Test
    void nothingIsCancelledWhileTheConsultationStillHasFailures() {
        UUID bad = stagedFinding();
        UUID unlisted = stagedFinding();
        failing.add(bad);

        CommitVoiceSessionResponse result = commit(List.of(bad), List.of());

        assertThat(result.session().status()).isEqualTo("PENDING_REVIEW");
        assertThat(result.notReviewed()).isZero();
        assertThat(row(unlisted).getConfirmationStatus()).isEqualTo(ConfirmationStatus.PENDING);
    }

    @Test
    void aFullyReviewedConsultationReportsNothingUnreviewed() {
        UUID a = stagedFinding();
        UUID b = stagedFinding();

        CommitVoiceSessionResponse result = commit(List.of(a), List.of(b));

        assertThat(result.notReviewed()).isZero();
    }

    // ── The report the dentist signs ────────────────────────────────────

    @Test
    void theReportIsSavedToTheRecordWhenTheConsultationCompletes() {
        UUID finding = stagedFinding();

        commit(List.of(finding), List.of());

        // Saved as a clinical note. Left on voice_sessions.summary alone it
        // reached no screen, while the review page said it was saved.
        verify(clinical).saveConsultationReport(PATIENT, sessionId, "Compte rendu", ACTOR);
    }

    @Test
    void theReportIsNotSavedWhileAFindingIsStillOutstanding() {
        UUID bad = stagedFinding();
        failing.add(bad);

        commit(List.of(bad), List.of());

        verify(clinical, never()).saveConsultationReport(any(), any(), any(), any());
    }

    @Test
    void theReportIsSavedOnceTheOutstandingFindingLands() {
        UUID bad = stagedFinding();
        failing.add(bad);
        commit(List.of(bad), List.of());

        failing.clear();
        commit(List.of(bad), List.of());

        verify(clinical, times(1)).saveConsultationReport(PATIENT, sessionId, "Compte rendu", ACTOR);
    }

    @Test
    void anEmptyReportIsNotSaved() {
        UUID finding = stagedFinding();
        CommitVoiceSessionRequest request = new CommitVoiceSessionRequest();
        request.setApprovedAuditIds(List.of(finding));
        request.setSummary("   ");

        service.commit(sessionId, request, ACTOR);

        verify(clinical, never()).saveConsultationReport(any(), any(), any(), any());
    }

    @Test
    void aReportThatCannotBeSavedLeavesTheConsultationOpenToSaveAgain() {
        UUID finding = stagedFinding();
        doThrow(new IllegalStateException("database unavailable"))
                .when(clinical).saveConsultationReport(any(), any(), any(), any());

        assertThatThrownBy(() -> commit(List.of(finding), List.of()))
                .isInstanceOf(IllegalStateException.class);

        // Not marked complete, so a second Save is still possible.
        assertThat(sessions.store.get(sessionId).getStatus()).isEqualTo(VoiceSessionStatus.PENDING_REVIEW);
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

        @Override
        public boolean transitionConfirmation(UUID id, ConfirmationStatus from, ConfirmationStatus to) {
            VoiceCommandAudit entry = store.get(id);
            if (entry == null || entry.getConfirmationStatus() != from) return false;
            entry.setConfirmationStatus(to);
            return true;
        }

        @Override
        public int scrubPatientData(UUID patientId) {
            List<VoiceCommandAudit> mine = findByPatient(patientId);
            mine.forEach(a -> {
                a.setTranscript(null);
                a.setEntities(null);
                a.setPreviousValue(null);
                a.setNewValue(null);
                a.setErrorMessage(null);
                a.setTargetId(null);
            });
            return mine.size();
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
