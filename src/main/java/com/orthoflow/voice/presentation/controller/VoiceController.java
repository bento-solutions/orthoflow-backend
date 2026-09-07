package com.orthoflow.voice.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.clinical.application.dto.PatientClinicalRecordResponse;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.clinical.domain.model.FindingCatalog;
import com.orthoflow.voice.application.dto.*;
import com.orthoflow.voice.application.service.SessionSummaryService;
import com.orthoflow.voice.application.service.SpeechToTextService;
import com.orthoflow.voice.application.service.VoiceAuditService;
import com.orthoflow.voice.application.service.VoiceCommandService;
import com.orthoflow.voice.application.service.VoiceSessionCommitService;
import com.orthoflow.voice.application.service.VoiceSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The voice pipeline's server side: session lifecycle, the audit trail, and
 * the confirm/reject gate on any clinical write a dictated command produced.
 *
 * <p>Every route here is DOCTOR/ADMIN only — {@code SecurityConfig} maps all
 * of {@code /voice/**} (reads included) to the same clinical floor as {@link
 * com.orthoflow.clinical.presentation.controller.ClinicalRecordController},
 * because a voice session's audit trail and summary contain the same patient
 * health information the clinical record does. A voice command inherits its
 * permission from the JWT it runs under, not from a separate voice-specific
 * permission model. The per-method {@code @PreAuthorize} annotations below
 * restate that floor for the writes and are redundant with the matrix by
 * design (defence in depth).
 */
@RestController
@RequestMapping("/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceSessionService voiceSessionService;
    private final VoiceCommandService voiceCommandService;
    private final ClinicalRecordService clinicalRecordService;
    private final VoiceAuditService voiceAuditService;
    private final SpeechToTextService speechToTextService;
    private final SessionSummaryService sessionSummaryService;
    private final VoiceSessionCommitService voiceSessionCommitService;
    private final CurrentUserProvider currentUserProvider;

    // ── Sessions ─────────────────────────────────────────────────────────

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public VoiceSessionResponse startSession(@RequestBody(required = false) StartVoiceSessionRequest request) {
        return voiceSessionService.start(request != null ? request : new StartVoiceSessionRequest(),
                currentUserProvider.requireUserId());
    }

    @PostMapping("/sessions/{sessionId}/end")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public VoiceSessionResponse endSession(
            @PathVariable UUID sessionId,
            @RequestBody(required = false) CompleteVoiceSessionRequest request) {
        return voiceSessionService.end(sessionId, request != null ? request : new CompleteVoiceSessionRequest(),
                currentUserProvider.requireUserId());
    }

    @GetMapping("/sessions/{sessionId}")
    public VoiceSessionResponse getSession(@PathVariable UUID sessionId) {
        return voiceSessionService.get(sessionId);
    }

    @GetMapping("/sessions")
    public List<VoiceSessionResponse> listSessions(@RequestParam UUID patientId) {
        return voiceSessionService.listByPatient(patientId);
    }

    /**
     * What this dictated examination actually persisted, read back from the
     * clinical tables rather than from anything the browser accumulated.
     *
     * That distinction is the point: the doctor signs off on the record as
     * stored, so a write that silently failed cannot appear in the summary
     * they confirm.
     */
    @GetMapping("/sessions/{sessionId}/summary")
    public PatientClinicalRecordResponse sessionSummary(@PathVariable UUID sessionId) {
        return clinicalRecordService.getSessionRecord(sessionId);
    }

    /**
     * The generated narrative for this examination, built from the session's
     * own audit trail.
     *
     * <p>Persists nothing, so the review page may call it as often as the
     * dentist edits what is included. The text only reaches the record if they
     * save it through {@link #commitSession}.
     *
     * <p>A response carrying {@code error} means no narrative could be
     * produced — generation is off, unconfigured, or the provider was
     * unreachable. The review page still renders the structured findings, so
     * that is a degraded page, not a blocked one.
     */
    @PostMapping("/sessions/{sessionId}/summarize")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public SessionSummaryResponse summariseSession(@PathVariable UUID sessionId) {
        return sessionSummaryService.summarise(sessionId);
    }

    /**
     * Writes a reviewed examination to the clinical record — the only path by
     * which a buffered session reaches the clinical tables.
     *
     * <p>Commands are named by audit id, never resent as values, so what
     * executes is re-derived from what the server itself recorded and showed.
     * Each one commits independently: the response reports what wrote, what
     * was discarded, and what failed, and a commit with any failure leaves the
     * session in {@code PENDING_REVIEW} rather than claiming it is saved.
     */
    @PostMapping("/sessions/{sessionId}/commit")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public CommitVoiceSessionResponse commitSession(
            @PathVariable UUID sessionId,
            @Valid @RequestBody CommitVoiceSessionRequest request) {
        return voiceSessionCommitService.commit(sessionId, request, currentUserProvider.requireUserId());
    }

    // ── Commands ────────────────────────────────────────────────────────

    /**
     * Logs one interpreted command. For a SAFE command this is called after
     * the client has already acted on it (navigation/read); for a CONFIRM
     * command it must be called with {@code confirmationStatus = PENDING} and
     * nothing has executed yet — see {@link #confirm}.
     */
    @PostMapping("/commands")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public VoiceCommandAuditResponse recordCommand(@Valid @RequestBody RecordVoiceCommandRequest request) {
        return voiceCommandService.record(request, currentUserProvider.requireUserId());
    }

    @PostMapping("/commands/{auditId}/confirm")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public VoiceCommandAuditResponse confirmCommand(@PathVariable UUID auditId) {
        return voiceCommandService.confirm(auditId, currentUserProvider.requireUserId());
    }

    @PostMapping("/commands/{auditId}/reject")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public VoiceCommandAuditResponse rejectCommand(@PathVariable UUID auditId) {
        return voiceCommandService.reject(auditId, currentUserProvider.requireUserId());
    }

    /** The trail for one patient, or for one session — pass exactly one. */
    @GetMapping("/commands")
    public List<VoiceCommandAuditResponse> listCommands(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID sessionId) {
        if (sessionId != null) return voiceAuditService.forSession(sessionId);
        if (patientId != null) return voiceAuditService.forPatient(patientId);
        throw new IllegalArgumentException("Provide patientId or sessionId");
    }

    // ── Speech-to-text ──────────────────────────────────────────────────

    /**
     * Transcribes one captured clip and hands the text back to the browser,
     * which runs it through the identical grammar → NLU → confirm pipeline a
     * typed or browser-recognised utterance takes. Nothing is written to the
     * clinical record here.
     *
     * <p>The primary capture path. The browser records a clip and posts it
     * here; its own {@code SpeechRecognition} remains as an offline fallback.
     * When {@code orthoflow.voice.stt.enabled} is false or the provider is
     * unreachable, the response carries an {@code error} tag and an empty
     * transcript so the client falls back to that recogniser rather than
     * losing the utterance.
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public TranscriptionResponse transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "prompt", required = false) String prompt) {
        return speechToTextService.transcribe(file, language, prompt);
    }

    // ── NLU fallback ────────────────────────────────────────────────────

    @PostMapping("/interpret")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public InterpretResponse interpret(@Valid @RequestBody InterpretRequest request) {
        return voiceCommandService.interpret(request);
    }

    // ── Lexicon ─────────────────────────────────────────────────────────

    /**
     * Publishes {@link FindingCatalog} so the browser's grammar can assert at
     * startup that its spoken-language synonyms map onto codes this server
     * actually accepts, per {@code FindingCatalog}'s own javadoc, which names
     * this exact path. (An equivalent read already exists at
     * {@code GET /clinical/finding-catalog} — kept as-is; this is the path the
     * domain model documents and the voice lexicon fetches from.)
     */
    @GetMapping("/lexicon")
    public Map<String, Object> lexicon() {
        List<Map<String, Object>> entries = FindingCatalog.all().values().stream()
                .map(def -> Map.<String, Object>of(
                        "code", def.code(),
                        "kind", def.kind().name(),
                        "impliedStatus", def.impliedStatus() == null ? "" : def.impliedStatus(),
                        "statusPriority", def.statusPriority()))
                .sorted((a, b) -> ((String) a.get("code")).compareTo((String) b.get("code")))
                .toList();
        return Map.of("codes", FindingCatalog.codes().stream().sorted().toList(), "definitions", entries);
    }
}
