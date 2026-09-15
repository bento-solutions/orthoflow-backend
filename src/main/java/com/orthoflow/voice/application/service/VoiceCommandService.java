package com.orthoflow.voice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.clinical.application.dto.*;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.clinical.domain.model.FindingStatus;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.voice.application.dto.CompleteVoiceSessionRequest;
import com.orthoflow.voice.application.dto.InterpretRequest;
import com.orthoflow.voice.application.dto.InterpretResponse;
import com.orthoflow.voice.application.dto.RecordVoiceCommandRequest;
import com.orthoflow.voice.application.dto.StartVoiceSessionRequest;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import com.orthoflow.voice.domain.model.ConfirmationStatus;
import com.orthoflow.voice.domain.model.RiskTier;
import com.orthoflow.voice.domain.model.VoiceCommandAudit;
import com.orthoflow.voice.domain.repository.VoiceCommandAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates one interpreted voice command end to end: audit it, and if it
 * is a write, hold it for explicit confirmation before touching a clinical
 * record.
 *
 * <p>The grammar itself lives in the browser (see
 * {@code frontend/src/app/core/voice/clinical-lexicon.ts} and AUDIT.md XII.4
 * §6 — "the deterministic grammar that handles structured clinical dictation
 * runs in the browser"). This service never parses raw speech; it receives an
 * already-resolved intent and entity map (or, for an utterance the browser
 * grammar couldn't parse, may pass it through {@link #interpret} to
 * {@link VoiceInterpretationService}'s pluggable {@link NluProvider}
 * fallback, off by default). What this service owns is the part that must be
 * authoritative: every attempt is audited (delegated to
 * {@link VoiceAuditService}, which owns writing the trail), and no clinical
 * write happens before the doctor has explicitly confirmed the *resolved*
 * values.
 *
 * <p>Two-phase write flow (audit XII.4 §2 and §3): {@link #record} persists
 * the interpreted command via {@link VoiceAuditService}. For a
 * {@link RiskTier#CONFIRM} command this is a {@link ConfirmationStatus#PENDING}
 * row and nothing is written yet; a {@link RiskTier#SAFE} command
 * (navigation/read) has already executed client-side by the time it is
 * logged here, so its row simply records that. {@link #confirm} is the only
 * path that ever calls into {@link ClinicalRecordService} or
 * {@link VoiceSessionService} for a write — it re-derives the command from
 * the audit row itself (never from a fresh client payload) so what executes
 * is exactly what was previewed and confirmed. {@link #reject} discards a
 * pending command without executing anything. Both update the *same* row
 * (read and re-saved directly through the repository, since confirming or
 * rejecting a specific in-flight row is a different operation from
 * {@link VoiceAuditService}'s write-once {@code record}) rather than
 * inserting a second one, so the trail reads as one line per command, not
 * two.
 *
 * <p>{@link CommandOutcome} has no dedicated "awaiting confirmation" value —
 * it was designed around terminal outcomes. A row newly created as PENDING
 * is stored with {@code outcome = CLARIFICATION}: the system understood the
 * utterance well enough to name a specific target and value, but is not yet
 * entitled to act on it — which is, functionally, the same "asked a question
 * instead of acting" the enum constant documents, just with the question
 * being "shall I?" rather than "which one?". A grammar-miss that is not
 * awaiting anyone's confirmation (nothing to confirm) is instead recorded as
 * {@code CLARIFICATION} + {@code confirmationStatus = REJECTED} — understood
 * as far as it goes, nothing pending, done.
 */
@Service
@RequiredArgsConstructor
public class VoiceCommandService {

    private final VoiceCommandAuditRepository auditRepository;
    private final VoiceAuditService voiceAuditService;
    private final VoiceInterpretationService voiceInterpretationService;
    private final ClinicalRecordService clinicalRecordService;
    private final VoiceSessionService voiceSessionService;
    private final ObjectMapper objectMapper;

    private record ExecutionResult(String targetType, String targetId, String previousValue, String newValue) {}

    // ── Recording ────────────────────────────────────────────────────────

    /**
     * Persists one line in the audit trail for whatever the browser attempted,
     * whether it matched, was rejected, or is now awaiting confirmation.
     * Never executes a write itself — see the class javadoc. Persistence,
     * enum parsing and the transcript-retention gate are
     * {@link VoiceAuditService}'s job; this method only enforces the one rule
     * that belongs to the confirm/execute lifecycle rather than to "how an
     * audit row is written": a CONFIRM-tier command may never arrive already
     * decided.
     */
    @Transactional
    public VoiceCommandAuditResponse record(RecordVoiceCommandRequest request, UUID actorId) {
        boolean isConfirmTier = RiskTier.CONFIRM.name().equalsIgnoreCase(request.getRiskTier());
        boolean isPending = ConfirmationStatus.PENDING.name().equalsIgnoreCase(request.getConfirmationStatus());
        if (isConfirmTier && !isPending) {
            // A CONFIRM-tier command must land here still awaiting its answer —
            // the write itself only ever happens through confirm().
            throw new ValidationException(
                    "A CONFIRM risk-tier command must be recorded as PENDING; the write happens on /confirm.");
        }
        return voiceAuditService.record(request, actorId);
    }

    // ── Confirm / reject ────────────────────────────────────────────────

    /**
     * Executes a pending command exactly as it was previewed, using only what
     * is already on the audit row — never a payload supplied fresh at confirm
     * time, so what runs cannot drift from what the doctor was shown.
     */
    /**
     * Deliberately <em>not</em> {@code @Transactional}.
     *
     * <p>The clinical write runs in {@link ClinicalRecordService}'s own
     * transaction. If it fails, that transaction is already marked
     * rollback-only, so recording the failure in the same transaction would
     * itself fail at commit — leaving the caller with an opaque 500 and, worse,
     * the audit row stuck at PENDING with no record that a write had even been
     * attempted. Keeping this method outside a transaction lets
     * {@link VoiceAuditService}'s {@code REQUIRES_NEW} finalisers record the
     * outcome either way.
     */
    public VoiceCommandAuditResponse confirm(UUID auditId, UUID actorId) {
        VoiceCommandAudit audit = requirePending(auditId);
        Map<String, Object> entities = parseEntities(audit.getEntities());

        try {
            ExecutionResult result = execute(audit.getIntent(), entities, audit.getPatientId(),
                    audit.getSessionId(), actorId);
            return voiceAuditService.markExecuted(auditId, result.targetType(), result.targetId(),
                    result.previousValue(), result.newValue());
        } catch (RuntimeException e) {
            // The doctor did confirm — the failure is in execution, not in the
            // decision to proceed, so confirmationStatus stays CONFIRMED and
            // the outcome records the failure separately.
            return voiceAuditService.markFailed(auditId, e.getMessage());
        }
    }

    public VoiceCommandAuditResponse reject(UUID auditId, UUID actorId) {
        requirePending(auditId);
        return voiceAuditService.markRejected(auditId);
    }

    private VoiceCommandAudit requirePending(UUID auditId) {
        VoiceCommandAudit audit = auditRepository.findById(auditId)
                .orElseThrow(() -> new NotFoundException("Voice command not found: " + auditId));
        if (audit.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new ValidationException("Voice command " + auditId + " is not awaiting confirmation ("
                    + audit.getConfirmationStatus() + ")");
        }
        return audit;
    }

    /**
     * The closed set of intents a CONFIRM-tier voice command may resolve to.
     * Anything else is rejected here even if it slipped past the browser
     * grammar, per audit XII.4 §7's context-scoping principle applied
     * server-side as the last gate before a clinical write.
     */
    private ExecutionResult execute(String intent, Map<String, Object> e, UUID patientId, UUID sessionId, UUID actorId) {
        return switch (intent) {
            case "clinical.addFinding" -> {
                if (patientId == null) throw new ValidationException("addFinding requires a patient");
                AddToothFindingRequest req = new AddToothFindingRequest();
                req.setFindingCode(str(e, "findingCode"));
                req.setSurface(strOrNull(e, "surface"));
                req.setSeverity(strOrNull(e, "severity"));
                req.setNote(strOrNull(e, "note"));
                req.setSource("voice");
                req.setSessionId(sessionId);
                String fdi = str(e, "fdi");
                ToothFindingResponse resp = clinicalRecordService.addFinding(patientId, fdi, req, actorId);
                yield new ExecutionResult("ToothFinding", resp.id().toString(), null,
                        fdi + ": " + resp.findingCode());
            }
            /*
             * The plural form, and the one the dictation path actually uses.
             *
             * "Old crown, recurrent caries underneath, crown needs replacement"
             * is one utterance the doctor previewed and confirmed once, but
             * three findings. Executing it as three separate confirmable
             * commands would make the doctor answer "yes" three times for one
             * sentence; recording only the last would discard two-thirds of
             * what they said. So the whole set travels on one audit row and
             * is written in one transaction.
             *
             * `retractIds` carries the correction case ("no, actually crown
             * replacement"): the superseded findings are withdrawn and the
             * replacements recorded together, so the record never passes
             * through a state where the tooth has neither.
             */
            case "clinical.addFindings" -> {
                if (patientId == null) throw new ValidationException("addFindings requires a patient");
                String fdi = str(e, "fdi");

                // Retract superseded findings atomically.
                List<UUID> retractUuids = listOf(e, "retractIds").stream()
                        .map(id -> UUID.fromString(String.valueOf(id)))
                        .toList();
                List<String> retracted = new ArrayList<>();
                if (!retractUuids.isEmpty()) {
                    retracted.addAll(clinicalRecordService.retractFindingsBatch(retractUuids, actorId)
                            .stream().map(r -> r.findingCode()).toList());
                }

                List<Object> findings = listOf(e, "findings");
                if (findings.isEmpty()) throw new ValidationException("addFindings requires at least one finding");

                // Build all requests, then write them in one transaction.
                List<AddToothFindingRequest> requests = new ArrayList<>();
                for (Object item : findings) {
                    if (!(item instanceof Map<?, ?> raw)) {
                        throw new ValidationException("Each finding must be an object");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> finding = (Map<String, Object>) raw;

                    AddToothFindingRequest req = new AddToothFindingRequest();
                    req.setFindingCode(str(finding, "code"));
                    req.setSurface(strOrNull(finding, "surface"));
                    req.setSeverity(strOrNull(finding, "severity"));
                    // A note on the utterance applies to every finding it
                    // produced unless the finding carried its own.
                    req.setNote(finding.get("note") != null ? strOrNull(finding, "note") : strOrNull(e, "note"));
                    req.setSource("voice");
                    req.setSessionId(sessionId);
                    requests.add(req);
                }

                var results = clinicalRecordService.addFindingsBatch(patientId, fdi, requests, actorId);
                List<String> ids = results.stream().map(r -> r.id().toString()).toList();
                List<String> codes = results.stream().map(r -> r.findingCode()).toList();

                yield new ExecutionResult("ToothFinding", String.join(",", ids),
                        retracted.isEmpty() ? null : String.join(",", retracted),
                        fdi + ": " + String.join(", ", codes));
            }
            case "clinical.retractFindings" -> {
                // Accepts explicit ids, or a tooth plus finding codes. The
                // dictated form is by code ("remove the sensitivity note from
                // that tooth"), and resolving those to ids here rather than in
                // the browser means the withdrawal targets whatever is
                // actually on the tooth at execution time.
                List<Object> ids = listOf(e, "findingIds");
                if (ids.isEmpty()) {
                    String fdi = strOrNull(e, "fdi");
                    List<Object> codes = listOf(e, "codes");
                    if (fdi != null && !codes.isEmpty() && patientId != null) {
                        List<String> wanted = codes.stream().map(String::valueOf).toList();
                        ids = clinicalRecordService.listFindingsForTooth(patientId, fdi).stream()
                                .filter(f -> wanted.contains(f.findingCode()))
                                .map(f -> (Object) f.id().toString())
                                .toList();
                    }
                }
                if (ids.isEmpty()) throw new ValidationException("Nothing matching that is recorded on this tooth");
                List<UUID> retractUuids = ids.stream()
                        .map(id -> UUID.fromString(String.valueOf(id)))
                        .toList();
                var results = clinicalRecordService.retractFindingsBatch(retractUuids, actorId);
                List<String> withdrawn = results.stream().map(r -> r.findingCode()).toList();
                List<String> withdrawnIds = results.stream().map(r -> r.id().toString()).toList();
                yield new ExecutionResult("ToothFinding", String.join(",", withdrawnIds),
                        String.join(", ", withdrawn), "(withdrawn)");
            }
            case "clinical.resolveFinding", "clinical.retractFinding" -> {
                UUID findingId = UUID.fromString(str(e, "findingId"));
                FindingStatus newStatus = intent.equals("clinical.resolveFinding")
                        ? FindingStatus.RESOLVED : FindingStatus.RETRACTED;
                ToothFindingResponse resp = clinicalRecordService.changeFindingStatus(findingId, newStatus, actorId);
                yield new ExecutionResult("ToothFinding", resp.id().toString(), "ACTIVE", newStatus.name());
            }
            case "clinical.addNote" -> {
                if (patientId == null) throw new ValidationException("addNote requires a patient");
                CreateClinicalNoteRequest req = new CreateClinicalNoteRequest();
                req.setCategory(strOrDefault(e, "category", "OBSERVATION"));
                req.setContent(str(e, "content"));
                req.setFdi(strOrNull(e, "fdi"));
                req.setSource("voice");
                req.setSessionId(sessionId);
                ClinicalNoteResponse resp = clinicalRecordService.addNote(patientId, req, actorId);
                yield new ExecutionResult("ClinicalNote", resp.id().toString(), null, truncate(resp.content()));
            }
            case "clinical.addAllergy" -> {
                if (patientId == null) throw new ValidationException("addAllergy requires a patient");
                AddAllergyRequest req = new AddAllergyRequest();
                req.setSubstance(str(e, "substance"));
                req.setReaction(strOrNull(e, "reaction"));
                req.setSeverity(strOrNull(e, "severity"));
                req.setSource("voice");
                req.setSessionId(sessionId);
                AllergyResponse resp = clinicalRecordService.addAllergy(patientId, req, actorId);
                yield new ExecutionResult("PatientAllergy", resp.id().toString(), null, resp.substance());
            }
            case "clinical.addMedicalHistory" -> {
                if (patientId == null) throw new ValidationException("addMedicalHistory requires a patient");
                AddMedicalHistoryRequest req = new AddMedicalHistoryRequest();
                req.setCategory(str(e, "category"));
                req.setLabel(str(e, "label"));
                req.setDetail(strOrNull(e, "detail"));
                req.setSource("voice");
                req.setSessionId(sessionId);
                MedicalHistoryResponse resp = clinicalRecordService.addMedicalHistory(patientId, req, actorId);
                yield new ExecutionResult("MedicalHistoryEntry", resp.id().toString(), null, resp.label());
            }
            case "voice.startSession" -> {
                StartVoiceSessionRequest req = new StartVoiceSessionRequest();
                req.setPatientId(patientId);
                req.setLocale(strOrNull(e, "locale"));
                var resp = voiceSessionService.start(req, actorId);
                yield new ExecutionResult("VoiceSession", resp.id().toString(), null, "ACTIVE");
            }
            case "voice.endSession" -> {
                if (sessionId == null) throw new ValidationException("endSession requires an active session");
                CompleteVoiceSessionRequest req = new CompleteVoiceSessionRequest();
                req.setStatus(strOrDefault(e, "status", "COMPLETED"));
                req.setSummary(strOrNull(e, "summary"));
                req.setConfirmed(true);
                var resp = voiceSessionService.end(sessionId, req, actorId);
                yield new ExecutionResult("VoiceSession", sessionId.toString(), "ACTIVE", resp.status());
            }
            default -> throw new ValidationException("Unknown voice intent: " + intent);
        };
    }

    // ── NLU fallback ────────────────────────────────────────────────────

    /**
     * Passes an utterance the browser grammar could not parse to
     * {@link VoiceInterpretationService} (the default {@code disabled}
     * provider simply returns a clarification prompt — see its javadoc for
     * why that is the deliberate default). The result is never executable on
     * its own; it still has to come back through {@link #record} and
     * {@link #confirm} like any other resolved command.
     */
    public InterpretResponse interpret(InterpretRequest request) {
        return voiceInterpretationService.interpret(request);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> parseEntities(String entitiesJson) {
        if (entitiesJson == null || entitiesJson.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(entitiesJson, Map.class);
            return parsed;
        } catch (JsonProcessingException e) {
            throw new ValidationException("Malformed entities JSON on voice command: " + e.getMessage());
        }
    }

    /**
     * A list-valued entity, defaulting to empty. Entities arrive as parsed
     * JSON from the audit row, so an absent key and a non-array value are
     * both ordinary client-shaped input rather than exceptional.
     */
    private List<Object> listOf(Map<String, Object> entities, String key) {
        Object value = entities.get(key);
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private String str(Map<String, Object> entities, String key) {
        Object value = entities.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ValidationException("Voice command is missing required entity '" + key + "'");
        }
        return value.toString();
    }

    private String strOrNull(Map<String, Object> entities, String key) {
        Object value = entities.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String strOrDefault(Map<String, Object> entities, String key, String fallback) {
        String value = strOrNull(entities, key);
        return value == null ? fallback : value;
    }

    private String truncate(String content) {
        if (content == null) return null;
        return content.length() <= 120 ? content : content.substring(0, 117) + "...";
    }

    private VoiceCommandAuditResponse toResponse(VoiceCommandAudit a) {
        return VoiceCommandAuditResponse.builder()
                .id(a.getId())
                .actorId(a.getActorId())
                .patientId(a.getPatientId())
                .sessionId(a.getSessionId())
                .occurredAt(a.getOccurredAt())
                .transcript(a.getTranscript())
                .locale(a.getLocale())
                .intent(a.getIntent())
                .entities(a.getEntities())
                .resolver(a.getResolver().name())
                .confidence(a.getConfidence() == null ? null : a.getConfidence().doubleValue())
                .module(a.getModule())
                .riskTier(a.getRiskTier().name())
                .confirmationStatus(a.getConfirmationStatus().name())
                .outcome(a.getOutcome().name())
                .targetType(a.getTargetType())
                .targetId(a.getTargetId())
                .previousValue(a.getPreviousValue())
                .newValue(a.getNewValue())
                .errorMessage(a.getErrorMessage())
                .undoneAt(a.getUndoneAt())
                .build();
    }
}
