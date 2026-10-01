package com.orthoflow.voice.application.service;

import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.voice.application.dto.CommitVoiceSessionRequest;
import com.orthoflow.voice.application.dto.CommitVoiceSessionResponse;
import com.orthoflow.voice.application.dto.CompleteVoiceSessionRequest;
import com.orthoflow.voice.application.dto.RecordVoiceCommandRequest;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import com.orthoflow.voice.domain.model.CommandOutcome;
import com.orthoflow.voice.domain.model.ConfirmationStatus;
import com.orthoflow.voice.domain.model.VoiceCommandAudit;
import com.orthoflow.voice.domain.model.VoiceSession;
import com.orthoflow.voice.domain.model.VoiceSessionStatus;
import com.orthoflow.voice.domain.repository.VoiceCommandAuditRepository;
import com.orthoflow.voice.domain.repository.VoiceSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a reviewed examination to the clinical record.
 *
 * <p>This is the commit step of the buffered workflow. During the examination
 * nothing is written: each dictated command is audited immediately as PENDING
 * — so a browser that dies mid-consultation still leaves a server-side record
 * of everything that was said — but the clinical tables are untouched until
 * the dentist has read the summary, corrected what was misheard, removed what
 * does not belong, and saved.
 *
 * <h2>Why this is not one transaction</h2>
 *
 * <p>The obvious implementation wraps the whole commit in {@code @Transactional}
 * so a consultation lands whole or not at all. That is wrong here, for two
 * reasons. {@link VoiceCommandService#confirm} is deliberately non-transactional
 * — its own javadoc explains that a clinical write failing inside a shared
 * transaction marks it rollback-only, which then makes recording the failure
 * fail too, leaving the row stuck at PENDING with no trace that anything was
 * attempted. And clinically, discarding twenty minutes of correct findings
 * because the twenty-first names a finding code the catalog has since dropped
 * is a worse outcome than writing the twenty and reporting the one.
 *
 * <p>So each command commits on its own and {@link CommitVoiceSessionResponse}
 * reports precisely what happened. The session is only marked COMPLETED when
 * every approved command actually wrote; if any failed it stays in
 * PENDING_REVIEW with the failures named, and the review page shows the
 * dentist what is still outstanding rather than telling them they are done.
 *
 * <h2>What "done" means</h2>
 *
 * <p>A session completes only when every command the dentist approved is
 * actually in the record. A command that failed is <em>not</em> dropped from
 * the next attempt: saving again retries it, and the dentist can instead
 * remove it at review, which dismisses it. Without that, the second press of
 * Save skipped the failed command because it was no longer pending, reported
 * no failures, and marked the consultation complete with the finding missing.
 *
 * <p>Saving also takes the session first, through its version. Two commits of
 * the same consultation — a double click, a second tab — would otherwise both
 * run every command before either noticed the other.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceSessionCommitService {

    private final VoiceSessionRepository voiceSessionRepository;
    private final VoiceCommandAuditRepository auditRepository;
    private final VoiceCommandService voiceCommandService;
    private final VoiceAuditService voiceAuditService;
    private final VoiceSessionService voiceSessionService;
    private final ClinicalRecordService clinicalRecordService;

    public CommitVoiceSessionResponse commit(UUID sessionId, CommitVoiceSessionRequest request, UUID actorId) {
        VoiceSession session = voiceSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Voice session not found: " + sessionId));

        if (session.getStatus() == VoiceSessionStatus.COMPLETED) {
            // Committing twice would re-execute every approved command and write
            // the whole consultation a second time.
            throw new ValidationException("This consultation has already been saved.");
        }
        if (session.getStatus() == VoiceSessionStatus.ABANDONED) {
            throw new ValidationException("This consultation was discarded and cannot be saved.");
        }

        claim(session);

        List<CommitVoiceSessionResponse.FailedCommand> failed = new ArrayList<>();
        int executed = 0;
        int rejected = 0;
        int amended = 0;

        // What the dentist took out, or replaced, must not be written even if
        // the same id also appears on the approved list by mistake.
        Set<UUID> discarded = new LinkedHashSet<>(request.getRejectedAuditIds());
        request.getAmendments().forEach(a -> discarded.add(a.getOriginalAuditId()));

        for (UUID auditId : request.getRejectedAuditIds()) {
            if (retire(requireSessionCommand(auditId, sessionId))) {
                rejected++;
            }
        }

        // A correction is recorded and run BEFORE the command it replaces is
        // retired. The other way round, the original is already gone by the
        // time its amendment is looked at, the amendment is taken for one an
        // earlier attempt had applied, and the finding is lost: the original
        // rejected, the replacement never made.
        for (CommitVoiceSessionRequest.Amendment amendment : request.getAmendments()) {
            VoiceCommandAudit original = requireSessionCommand(amendment.getOriginalAuditId(), sessionId);
            // An amendment an earlier attempt already applied has had its
            // original retired; its replacement is approved by id instead.
            if (!isPending(original) && !hasFailed(original)) {
                continue;
            }
            VoiceCommandAuditResponse replacement =
                    recordAmendment(original, amendment, sessionId, actorId);
            amended++;
            if (retire(original)) {
                rejected++;
            }
            if (executeApproved(replacement.id(), sessionId, failed) == Attempt.EXECUTED) {
                executed++;
            }
        }

        for (UUID auditId : request.getApprovedAuditIds()) {
            if (discarded.contains(auditId)) {
                continue;
            }
            if (executeApproved(auditId, sessionId, failed) == Attempt.EXECUTED) {
                executed++;
            }
        }

        VoiceSession refreshed = voiceSessionRepository.findById(sessionId).orElseThrow();
        CompleteVoiceSessionRequest completion = new CompleteVoiceSessionRequest();
        completion.setSummary(request.getSummary());

        // A command that failed and is on no list — a correction's replacement
        // made at an earlier Save, which a reloaded review page has never seen —
        // is still outstanding. Completing over it would lose it without a word.
        Set<UUID> reported = new HashSet<>();
        failed.forEach(f -> reported.add(f.auditId()));
        for (VoiceCommandAudit left : auditRepository.findBySession(sessionId)) {
            boolean unfinished = VoiceAuditService.isUnfinished(left);
            if ((hasFailed(left) || unfinished) && reported.add(left.getId())) {
                failed.add(CommitVoiceSessionResponse.FailedCommand.builder()
                        .auditId(left.getId())
                        .intent(left.getIntent())
                        .errorMessage(unfinished
                                ? "Not saved: it was started and never finished. Remove it, or discard the examination."
                                : left.getErrorMessage())
                        .build());
            }
        }

        int notReviewed = 0;
        if (failed.isEmpty()) {
            // Whatever is still pending now was on no list at all. Left alone
            // it could be confirmed later through the command API and written
            // into a chart under a consultation that is already closed.
            for (VoiceCommandAudit left : auditRepository.findBySession(sessionId)) {
                if (isPending(left)) {
                    voiceAuditService.markNotReviewed(left.getId());
                    notReviewed++;
                }
            }
            if (notReviewed > 0) {
                log.warn("Voice session {} saved with {} dictated command(s) that were never on the review list.",
                        sessionId, notReviewed);
            }
        }

        if (failed.isEmpty()) {
            // The review page tells the dentist this text is saved to the
            // record as a note. Before the session is marked complete, so a
            // report that fails to save leaves the consultation open to be
            // saved again — the save is idempotent per examination.
            saveReport(refreshed, request.getSummary(), actorId);
            completion.setStatus(VoiceSessionStatus.COMPLETED.name());
            completion.setConfirmed(true);
        } else {
            // Not finished. Leaving it COMPLETED would tell the dentist the
            // consultation is saved while findings they approved are missing
            // from the record.
            completion.setStatus(VoiceSessionStatus.PENDING_REVIEW.name());
            completion.setConfirmed(false);
            log.warn("Voice session {} committed with {} failed command(s); left in PENDING_REVIEW.",
                    sessionId, failed.size());
        }

        return CommitVoiceSessionResponse.builder()
                .session(voiceSessionService.end(refreshed.getId(), completion, actorId))
                .executed(executed)
                .rejected(rejected)
                .amended(amended)
                .notReviewed(notReviewed)
                .failed(failed)
                .build();
    }

    /**
     * The narrative as a clinical note. Nothing to save when the dentist left
     * it empty, or when the examination was not about a patient.
     */
    private void saveReport(VoiceSession session, String summary, UUID actorId) {
        if (session.getPatientId() == null || summary == null || summary.isBlank()) {
            return;
        }
        clinicalRecordService.saveConsultationReport(session.getPatientId(), session.getId(), summary, actorId);
    }

    /**
     * Takes the session before anything runs. Saving it bumps its version, so
     * a second commit that read the same version fails here with an optimistic
     * lock error — a 409 — instead of running every command over again. The
     * version was added for exactly this, but only checking it when the
     * session was written back at the very end meant the check fired after
     * every clinical write had already happened.
     */
    private void claim(VoiceSession session) {
        session.setEndedAt(OffsetDateTime.now());
        voiceSessionRepository.save(session);
    }

    /**
     * Takes a command out of the examination: a pending one is rejected, one
     * that failed is dismissed (its failure stays in the trail). True when it
     * was one of those; anything else — already written, already retired — is
     * left as it is.
     */
    private boolean retire(VoiceCommandAudit audit) {
        if (isPending(audit)) {
            voiceAuditService.markRejected(audit.getId());
            return true;
        }
        if (hasFailed(audit) || VoiceAuditService.isUnfinished(audit)) {
            voiceAuditService.markDismissed(audit.getId());
            return true;
        }
        return false;
    }

    /** What happened to one approved command. */
    private enum Attempt { EXECUTED, ALREADY_RECORDED, SKIPPED, FAILED }

    /**
     * Runs one approved command, or accounts for why it did not need running.
     * Anything approved that is not in the record afterwards is added to
     * {@code failed}, so the session cannot complete over it.
     */
    private Attempt executeApproved(UUID auditId, UUID sessionId,
                                    List<CommitVoiceSessionResponse.FailedCommand> failed) {
        VoiceCommandAudit audit = requireSessionCommand(auditId, sessionId);

        if (audit.getOutcome() == CommandOutcome.EXECUTED) {
            // Written by an earlier attempt or confirmed mid-examination.
            return Attempt.ALREADY_RECORDED;
        }
        if (audit.getConfirmationStatus() == ConfirmationStatus.REJECTED
                || audit.getConfirmationStatus() == ConfirmationStatus.CANCELLED) {
            // The dentist took it back; approving it now does not bring it back.
            return Attempt.SKIPPED;
        }

        if (hasFailed(audit)) {
            // A second Save is a retry. The failure may have been transient (a
            // finding code the catalog now accepts, a dropped connection).
            voiceAuditService.reopenFailed(auditId);
        } else if (!isPending(audit)) {
            failed.add(CommitVoiceSessionResponse.FailedCommand.builder()
                    .auditId(auditId)
                    .intent(audit.getIntent())
                    .errorMessage("Not saved: the command is in an unexpected state ("
                            + audit.getConfirmationStatus() + "/" + audit.getOutcome() + ").")
                    .build());
            return Attempt.FAILED;
        }

        VoiceCommandAuditResponse result = voiceCommandService.confirm(auditId, audit.getActorId());
        if ("FAILED".equals(result.outcome())) {
            failed.add(CommitVoiceSessionResponse.FailedCommand.builder()
                    .auditId(auditId)
                    .intent(result.intent())
                    .errorMessage(result.errorMessage())
                    .build());
            return Attempt.FAILED;
        }
        return Attempt.EXECUTED;
    }

    /**
     * Records the dentist's correction as its own command. {@code resolver =
     * manual} and {@code confidence = null} are the honest values: a human
     * typed this at review, so attributing it to the grammar or to a model —
     * and giving it a recognition confidence it never had — would misdescribe
     * the trail.
     */
    private VoiceCommandAuditResponse recordAmendment(VoiceCommandAudit original,
                                                      CommitVoiceSessionRequest.Amendment amendment,
                                                      UUID sessionId,
                                                      UUID actorId) {
        RecordVoiceCommandRequest replacement = new RecordVoiceCommandRequest();
        replacement.setPatientId(original.getPatientId());
        replacement.setSessionId(sessionId);
        replacement.setLocale(original.getLocale());
        replacement.setIntent(amendment.getIntent());
        replacement.setEntities(amendment.getEntities());
        replacement.setResolver("manual");
        replacement.setModule(original.getModule());
        replacement.setRiskTier(original.getRiskTier().name());
        replacement.setConfirmationStatus("PENDING");
        replacement.setOutcome("CLARIFICATION");
        replacement.setPreviousValue(original.getEntities());
        replacement.setErrorMessage("Corrected at review, replacing command " + original.getId());
        return voiceCommandService.record(replacement, actorId);
    }

    /**
     * A command must belong to the session being committed. Without this check
     * a crafted payload could confirm pending writes from another patient's
     * consultation under this session's sign-off.
     */
    private VoiceCommandAudit requireSessionCommand(UUID auditId, UUID sessionId) {
        VoiceCommandAudit audit = auditRepository.findById(auditId)
                .orElseThrow(() -> new NotFoundException("Voice command not found: " + auditId));
        if (!sessionId.equals(audit.getSessionId())) {
            throw new ValidationException("Voice command " + auditId + " does not belong to session " + sessionId);
        }
        return audit;
    }

    private static boolean isPending(VoiceCommandAudit audit) {
        return audit.getConfirmationStatus() == ConfirmationStatus.PENDING;
    }

    /** Confirmed, ran, and did not land — a command a later Save may retry. */
    private static boolean hasFailed(VoiceCommandAudit audit) {
        return audit.getConfirmationStatus() == ConfirmationStatus.CONFIRMED
                && audit.getOutcome() == CommandOutcome.FAILED;
    }
}
