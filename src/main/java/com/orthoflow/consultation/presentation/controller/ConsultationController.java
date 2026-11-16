package com.orthoflow.consultation.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.consultation.application.dto.CommitConsultationRequest;
import com.orthoflow.consultation.application.dto.CommitConsultationResponse;
import com.orthoflow.consultation.application.dto.ConsultationConfigResponse;
import com.orthoflow.consultation.application.dto.ConsultationLogResponse;
import com.orthoflow.consultation.application.dto.ConsultationResponse;
import com.orthoflow.consultation.application.dto.ExtractionResponse;
import com.orthoflow.consultation.application.dto.StartConsultationRequest;
import com.orthoflow.consultation.application.dto.TranscriptRequest;
import com.orthoflow.consultation.application.service.ConsultationAudioService;
import com.orthoflow.consultation.application.service.ConsultationCommitService;
import com.orthoflow.consultation.application.service.ConsultationLogService;
import com.orthoflow.consultation.application.service.ConsultationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full-consultation recording: the conversation is transcribed, what matters in
 * it is proposed, and the doctor validates it before anything reaches the record.
 *
 * <p>DOCTOR/ADMIN only — {@code SecurityConfig} maps all of {@code /consultations/**}
 * to the clinical floor, since a transcript is the most sensitive text in the
 * system. The method annotations restate it (defence in depth). Every route is
 * a 404 while {@code orthoflow.consultation.enabled} is false.
 */
@RestController
@RequestMapping("/consultations")
@RequiredArgsConstructor
public class ConsultationController {

    private final ConsultationService consultationService;
    private final ConsultationCommitService commitService;
    private final ConsultationLogService logService;
    private final ConsultationAudioService audioService;
    private final CurrentUserProvider currentUserProvider;

    /** Whether to offer the feature, and whether a model reads the conversation. */
    @GetMapping("/config")
    public ConsultationConfigResponse config() {
        return consultationService.config();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ConsultationResponse start(@Valid @RequestBody StartConsultationRequest request) {
        return consultationService.start(request, currentUserProvider.requireUserId());
    }

    /** A patient's consultations, newest first, without their transcripts. */
    @GetMapping
    public List<ConsultationResponse> list(@RequestParam UUID patientId) {
        return consultationService.listForPatient(patientId);
    }

    /** The patient's unfinished consultation, or 204 — offered back when their dossier opens. */
    @GetMapping("/open")
    public ResponseEntity<ConsultationResponse> open(@RequestParam UUID patientId) {
        ConsultationResponse open = consultationService.findOpen(patientId);
        return open == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(open);
    }

    @GetMapping("/{id}")
    public ConsultationResponse get(@PathVariable UUID id) {
        return consultationService.get(id);
    }

    /**
     * The consultation log: what was said and when, the commands the examination
     * understood and what became of them, and what the doctor validated. Open
     * to the doctor after the consultation is saved, for as long as it is kept.
     */
    @GetMapping("/{id}/log")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ConsultationLogResponse log(@PathVariable UUID id) {
        return logService.log(id);
    }

    /**
     * Reads the conversation so far and says what it established. Also saves the
     * transcript it is sent. Rate-limited per user: it can call a paid model.
     */
    @PostMapping("/{id}/extract")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ExtractionResponse extract(@PathVariable UUID id, @Valid @RequestBody TranscriptRequest request) {
        logService.record(id, request.getLines());
        return consultationService.extract(id, request.getTranscript(), request.getMode(), request.getKnown());
    }

    /**
     * Keeps the conversation so far without reading it: what a consultation in the deferred mode
     * sends while it records (nothing is read until it ends), so a crash loses nothing.
     */
    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void keepLines(@PathVariable UUID id, @Valid @RequestBody TranscriptRequest request) {
        logService.record(id, request.getLines());
        consultationService.keepTranscript(id, request.getTranscript());
    }

    /**
     * The recording of the conversation, sent once when the consultation ends (again replaces it).
     * {@code startedAt} is when the browser started recording, so each line can be found in it.
     */
    @PostMapping(value = "/{id}/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ConsultationResponse uploadAudio(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startedAt,
                                            @RequestParam(required = false) Long durationMs) {
        return audioService.store(id, file, startedAt, durationMs, currentUserProvider.requireUserId());
    }

    /** The recording, to listen to beside the timestamped transcript. Never cached by a shared proxy. */
    @GetMapping("/{id}/audio")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ResponseEntity<InputStreamResource> audio(@PathVariable UUID id) {
        ConsultationAudioService.Playable playable = audioService.open(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(playable.file().getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.parseMediaType(playable.file().getContentType()))
                .contentLength(playable.file().getSizeBytes())
                .body(new InputStreamResource(playable.content()));
    }

    /** Removes the recording; the transcript and everything validated stay. */
    @DeleteMapping("/{id}/audio")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ConsultationResponse deleteAudio(@PathVariable UUID id) {
        return audioService.delete(id);
    }

    /**
     * Keeps the doctor's decisions on the panel so a reload does not undo them.
     * Does not move the consultation's version, so it never conflicts with a
     * reading or a save.
     */
    @PutMapping("/{id}/review-state")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveReviewState(@PathVariable UUID id, @RequestBody JsonNode state) {
        consultationService.saveReviewState(id, state);
    }

    /** The doctor has called the consultation; the chart commands start to work. */
    @PostMapping("/{id}/examination")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ConsultationResponse beginExamination(@PathVariable UUID id) {
        return consultationService.beginExamination(id);
    }

    /** Recording stops; the doctor reviews. */
    @PostMapping("/{id}/end")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ConsultationResponse end(@PathVariable UUID id, @Valid @RequestBody TranscriptRequest request) {
        logService.record(id, request.getLines());
        return consultationService.end(id, request.getTranscript());
    }

    /**
     * The doctor continues a consultation that is waiting for review: recording starts again, and
     * everything caught so far is kept.
     */
    @PostMapping("/{id}/continue")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ConsultationResponse continueConsultation(@PathVariable UUID id) {
        return consultationService.reopen(id);
    }

    /**
     * Saves what the doctor validated. When a chart finding fails to write the
     * response says so ({@code saved: false}) and nothing else is written.
     */
    @PostMapping("/{id}/commit")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public CommitConsultationResponse commit(@PathVariable UUID id,
                                             @Valid @RequestBody CommitConsultationRequest request) {
        return commitService.commit(id, request, currentUserProvider.requireUserId());
    }

    /** Throws the consultation away, transcript included. */
    @PostMapping("/{id}/abandon")
    @PreAuthorize("hasAnyRole('DOCTOR', 'ADMIN')")
    public ConsultationResponse abandon(@PathVariable UUID id) {
        return consultationService.abandon(id);
    }
}
