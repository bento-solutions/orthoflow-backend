package com.orthoflow.voice.application.service;

import com.orthoflow.voice.application.dto.RecordVoiceCommandRequest;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import com.orthoflow.voice.domain.model.*;
import com.orthoflow.voice.domain.repository.VoiceCommandAuditRepository;
import com.orthoflow.voice.infrastructure.nlu.VoiceNluProperties;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Writes the voice audit trail. Deliberately separate from the services that
 * perform the clinical writes: the audit row must be recorded whether or not
 * the command executed, including when it was refused.
 */
@Service
@RequiredArgsConstructor
public class VoiceAuditService {

    private final VoiceCommandAuditRepository auditRepository;
    private final VoiceNluProperties properties;

    @Transactional
    public VoiceCommandAuditResponse record(RecordVoiceCommandRequest request, UUID actorId) {
        VoiceCommandAudit entry = VoiceCommandAudit.builder()
                .actorId(actorId)
                .patientId(request.getPatientId())
                .sessionId(request.getSessionId())
                // Retention of the raw utterance is a deployment policy, applied
                // here rather than trusted to the client: a browser that keeps
                // sending transcripts must not be able to override the clinic's
                // decision not to store them.
                .transcript(properties.getAudit().isStoreTranscript() ? request.getTranscript() : null)
                .locale(request.getLocale())
                .intent(request.getIntent())
                .entities(request.getEntities())
                .resolver(parseEnum(ResolverKind.class, request.getResolver(), "resolver", false))
                .confidence(request.getConfidence() == null ? null
                        : BigDecimal.valueOf(request.getConfidence()).setScale(3, RoundingMode.HALF_UP))
                .module(request.getModule())
                .riskTier(parseEnum(RiskTier.class, request.getRiskTier(), "risk tier", true))
                .confirmationStatus(parseEnum(ConfirmationStatus.class, request.getConfirmationStatus(),
                        "confirmation status", true))
                .outcome(parseEnum(CommandOutcome.class, request.getOutcome(), "outcome", true))
                .targetType(request.getTargetType())
                .targetId(request.getTargetId())
                .previousValue(request.getPreviousValue())
                .newValue(request.getNewValue())
                .errorMessage(request.getErrorMessage())
                .build();

        return toResponse(auditRepository.save(entry));
    }

    /**
     * Records the outcome of a command that was confirmed and executed.
     *
     * <p>Runs in its own transaction ({@code REQUIRES_NEW}) for the same
     * reason {@link #markFailed} does — see the note there.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VoiceCommandAuditResponse markExecuted(
            UUID auditId, String targetType, String targetId, String previousValue, String newValue) {
        VoiceCommandAudit entry = require(auditId);
        entry.setConfirmationStatus(ConfirmationStatus.CONFIRMED);
        entry.setOutcome(CommandOutcome.EXECUTED);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setPreviousValue(previousValue);
        entry.setNewValue(newValue);
        return toResponse(auditRepository.save(entry));
    }

    /**
     * Records that a confirmed command failed during execution.
     *
     * <p>{@code REQUIRES_NEW} is load-bearing, not decoration. The failure
     * arrives as an exception thrown from inside a {@code @Transactional}
     * clinical write, which has already marked the surrounding transaction
     * rollback-only; writing the audit row in that transaction would fail at
     * commit with {@code UnexpectedRollbackException}. The caller would see an
     * opaque 500, and — far worse — the row would stay PENDING forever, so an
     * attempted clinical write that failed would leave no trace of having been
     * attempted. A separate transaction records the failure regardless of what
     * happened to the write.
     *
     * <p>{@code confirmationStatus} stays CONFIRMED: the doctor did confirm,
     * and the failure was in execution, not in the decision to proceed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VoiceCommandAuditResponse markFailed(UUID auditId, String errorMessage) {
        VoiceCommandAudit entry = require(auditId);
        entry.setConfirmationStatus(ConfirmationStatus.CONFIRMED);
        entry.setOutcome(CommandOutcome.FAILED);
        entry.setErrorMessage(errorMessage);
        return toResponse(auditRepository.save(entry));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VoiceCommandAuditResponse markRejected(UUID auditId) {
        VoiceCommandAudit entry = require(auditId);
        entry.setConfirmationStatus(ConfirmationStatus.REJECTED);
        entry.setOutcome(CommandOutcome.REJECTED);
        return toResponse(auditRepository.save(entry));
    }

    /**
     * Puts a command that failed back in the queue so a second attempt can
     * run it. Only a command that was confirmed and then failed qualifies;
     * anything else being "reopened" would be a way to re-run a write that
     * already happened.
     *
     * <p>The failure is not erased: it stays in the message as history, so the
     * row reads "Retried after: …" whatever the second attempt did.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VoiceCommandAuditResponse reopenFailed(UUID auditId) {
        VoiceCommandAudit entry = require(auditId);
        requireFailed(entry);
        String previous = entry.getErrorMessage();
        entry.setConfirmationStatus(ConfirmationStatus.PENDING);
        entry.setOutcome(CommandOutcome.CLARIFICATION);
        entry.setErrorMessage(previous == null || previous.isBlank() ? "Retried." : "Retried after: " + previous);
        return toResponse(auditRepository.save(entry));
    }

    /**
     * The dentist chose not to retry a command that failed. It keeps its FAILED
     * outcome and its error — the trail still says the write was attempted and
     * did not land — and is marked CANCELLED so the consultation is no longer
     * waiting on it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VoiceCommandAuditResponse markDismissed(UUID auditId) {
        VoiceCommandAudit entry = require(auditId);
        requireFailed(entry);
        entry.setConfirmationStatus(ConfirmationStatus.CANCELLED);
        return toResponse(auditRepository.save(entry));
    }

    private static void requireFailed(VoiceCommandAudit entry) {
        if (entry.getConfirmationStatus() != ConfirmationStatus.CONFIRMED
                || entry.getOutcome() != CommandOutcome.FAILED) {
            throw new ValidationException("Voice command " + entry.getId() + " did not fail ("
                    + entry.getConfirmationStatus() + "/" + entry.getOutcome() + ").");
        }
    }

    private VoiceCommandAudit require(UUID auditId) {
        return auditRepository.findById(auditId)
                .orElseThrow(() -> new NotFoundException("Voice audit entry not found: " + auditId));
    }

    /**
     * Marks a previously executed command as undone. The original row keeps
     * its EXECUTED outcome and gains an {@code undoneAt} — rewriting it would
     * erase the fact that the write happened at all, which is the opposite of
     * what an audit trail is for.
     */
    @Transactional
    public VoiceCommandAuditResponse markUndone(UUID auditId) {
        VoiceCommandAudit entry = auditRepository.findById(auditId)
                .orElseThrow(() -> new NotFoundException("Voice audit entry not found: " + auditId));
        entry.setUndoneAt(OffsetDateTime.now());
        return toResponse(auditRepository.save(entry));
    }

    @Transactional(readOnly = true)
    public List<VoiceCommandAuditResponse> forPatient(UUID patientId) {
        return auditRepository.findByPatient(patientId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<VoiceCommandAuditResponse> forSession(UUID sessionId) {
        return auditRepository.findBySession(sessionId).stream().map(this::toResponse).toList();
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label, boolean upper) {
        if (value == null) throw new ValidationException("Missing " + label);
        String normalized = upper ? value.trim().toUpperCase(Locale.ROOT) : value.trim().toLowerCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid " + label + ": " + value);
        }
    }

    private VoiceCommandAuditResponse toResponse(VoiceCommandAudit e) {
        return VoiceCommandAuditResponse.builder()
                .id(e.getId())
                .actorId(e.getActorId())
                .patientId(e.getPatientId())
                .sessionId(e.getSessionId())
                .occurredAt(e.getOccurredAt())
                .transcript(e.getTranscript())
                .locale(e.getLocale())
                .intent(e.getIntent())
                .entities(e.getEntities())
                .resolver(e.getResolver().name())
                .confidence(e.getConfidence() == null ? null : e.getConfidence().doubleValue())
                .module(e.getModule())
                .riskTier(e.getRiskTier().name())
                .confirmationStatus(e.getConfirmationStatus().name())
                .outcome(e.getOutcome().name())
                .targetType(e.getTargetType())
                .targetId(e.getTargetId())
                .previousValue(e.getPreviousValue())
                .newValue(e.getNewValue())
                .errorMessage(e.getErrorMessage())
                .undoneAt(e.getUndoneAt())
                .build();
    }
}
