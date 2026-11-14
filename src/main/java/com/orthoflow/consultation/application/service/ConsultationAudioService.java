package com.orthoflow.consultation.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.consultation.application.dto.ConsultationResponse;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * The audio of a consultation, kept beside its timestamped transcript so the doctor can listen to
 * what a line, a proposal or a command was read from (docs/CONSULTATION.md, "The recording").
 *
 * <p>Kept only where the transcript is ({@link ConsultationExtractionProperties#audioKept()}), and
 * erased with it: a discarded consultation, a save with retention off, an idle discard and a
 * patient's erasure all take the recording too. The browser records the whole conversation and
 * sends it once, when the consultation ends; sending it again replaces it.
 */
@Service
@RequiredArgsConstructor
public class ConsultationAudioService {

    /** Longer than any consultation: a duration past it is a broken browser clock, not a length. */
    private static final long MAX_DURATION_MS = 24L * 60 * 60 * 1000;

    private final ConsultationRepository consultations;
    private final ConsultationService consultationService;
    private final ConsultationExtractionProperties properties;
    private final FileService files;

    /** A recording and what it is, ready to stream. */
    public record Playable(StoredFile file, InputStream content) {}

    @Transactional
    public ConsultationResponse store(UUID id, MultipartFile upload, OffsetDateTime startedAt, Long durationMs, UUID actorId) {
        consultationService.requireEnabled();
        if (!properties.audioKept()) {
            throw new ValidationException("This clinic does not keep the audio of consultations.");
        }
        Consultation consultation = consultationService.require(id);
        if (consultation.getStatus() == ConsultationStatus.ABANDONED) {
            throw new ValidationException("This consultation was discarded; its recording is not kept.");
        }
        if (upload == null || upload.isEmpty()) {
            throw new ValidationException("The recording is empty");
        }
        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long cap = properties.getMaxAudioMegabytes() * 1024L * 1024L;
        StoredFile stored = files.storeAudio(consultation.getPracticeId(), FileOwnerType.CONSULTATION_AUDIO, id,
                name(consultation, bytes), bytes, cap, actorId);
        // Sent again (a retry after a failed upload, a second end): the new one replaces the old.
        consultationService.eraseAudio(consultation);
        consultation.setAudioFileId(stored.getId());
        consultation.setAudioStartedAt(startedAt != null ? startedAt : consultation.getStartedAt());
        consultation.setAudioDurationMs(durationMs != null && durationMs > 0 && durationMs <= MAX_DURATION_MS ? durationMs : null);
        consultation.touch();
        return consultationService.toResponse(consultations.save(consultation), false);
    }

    @Transactional(readOnly = true)
    public Playable open(UUID id) {
        consultationService.requireEnabled();
        Consultation consultation = consultationService.require(id);
        if (consultation.getAudioFileId() == null) {
            throw new NotFoundException("This consultation has no recording.");
        }
        StoredFile file = files.require(consultation.getPracticeId(), consultation.getAudioFileId());
        return new Playable(file, files.open(file));
    }

    /** The doctor removes the recording; the transcript stays. */
    @Transactional
    public ConsultationResponse delete(UUID id) {
        consultationService.requireEnabled();
        Consultation consultation = consultationService.require(id);
        consultationService.eraseAudio(consultation);
        return consultationService.toResponse(consultations.save(consultation), false);
    }

    /** "consultation-2026-11-13-1004.webm": when it was, in the clinic's zone, and what it is. */
    private String name(Consultation consultation, byte[] bytes) {
        String type = FileService.sniffAudio(bytes);
        String extension = switch (type) {
            case "audio/ogg" -> "ogg";
            case "audio/mp4" -> "m4a";
            case "audio/wav" -> "wav";
            default -> "webm";
        };
        OffsetDateTime at = consultation.getStartedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : consultation.getStartedAt();
        String when = at.atZoneSameInstant(java.time.ZoneId.of(properties.getTimezone()))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        return "consultation-" + when + "." + extension;
    }
}
