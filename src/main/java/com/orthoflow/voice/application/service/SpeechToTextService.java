package com.orthoflow.voice.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.voice.application.dto.TranscriptionResponse;
import com.orthoflow.voice.infrastructure.stt.SpeechToTextProperties;
import com.orthoflow.voice.infrastructure.stt.TranscriptionProvider;
import com.orthoflow.voice.infrastructure.stt.TranscriptionResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Server-side capture-to-text. The browser records a clip and posts it here;
 * this hands back a transcript that re-enters the client pipeline exactly
 * where a browser-recognised utterance would.
 *
 * <p>Off by default. When {@code orthoflow.voice.stt.enabled} is false — or
 * the provider is unreachable — this returns a transcript-less {@link
 * TranscriptionResponse} carrying an {@code error} tag rather than throwing,
 * so the client falls back to its own recogniser. Only a malformed request
 * (no audio, or a clip past the configured ceiling) is a hard 400.
 */
@Service
@Slf4j
public class SpeechToTextService {

    private final SpeechToTextProperties properties;
    private final List<TranscriptionProvider> providers;

    public SpeechToTextService(SpeechToTextProperties properties, List<TranscriptionProvider> providers) {
        this.properties = properties;
        this.providers = providers;
    }

    /**
     * The provider named by {@code orthoflow.voice.stt.provider}, or empty if
     * that name matches none. An unrecognised provider name is treated as
     * "off" rather than silently falling back to whichever one happens to be
     * first on the classpath — a deployment that meant to send audio to Gemini
     * and typoed the name should get browser recognition and a warning in the
     * log, not Groq.
     */
    private Optional<TranscriptionProvider> selectProvider() {
        String configured = properties.getProvider();
        return providers.stream()
                .filter(p -> p.name().equalsIgnoreCase(configured))
                .findFirst();
    }

    @PostConstruct
    void reportConfiguration() {
        Optional<TranscriptionProvider> provider = selectProvider();
        if (!properties.isEnabled()) {
            log.info("Server-side speech-to-text disabled. The browser's own SpeechRecognition is used; "
                    + "set orthoflow.voice.stt.enabled=true with a provider and API key to route capture "
                    + "through a hosted recogniser instead.");
        } else if (provider.isEmpty()) {
            log.warn("orthoflow.voice.stt.provider='{}' matches no registered provider (known: {}) — "
                            + "/voice/transcribe will report stt-not-configured and the client will fall "
                            + "back to browser recognition.",
                    properties.getProvider(), providers.stream().map(TranscriptionProvider::name).toList());
        } else if (!provider.get().isConfigured()) {
            log.warn("orthoflow.voice.stt.enabled=true with provider={} but its api-key/base-url is not "
                            + "set — /voice/transcribe will report stt-not-configured and the client will "
                            + "fall back to browser recognition.",
                    provider.get().name());
        } else {
            log.info("Server-side speech-to-text enabled: provider={} model={}",
                    provider.get().name(), properties.activeModel());
        }
    }

    /**
     * @param audio    the recorded clip (webm/ogg/wav/mp3/m4a…)
     * @param language ISO-639-1 override, or null/blank to let Whisper detect
     * @param prompt   optional bias text (names, clinical terms), or null
     */
    public TranscriptionResponse transcribe(MultipartFile audio, String language, String prompt) {
        if (audio == null || audio.isEmpty()) {
            throw new ValidationException("No audio was received.");
        }
        if (audio.getSize() > properties.getMaxAudioBytes()) {
            throw new ValidationException("That recording is too large (limit "
                    + (properties.getMaxAudioBytes() / (1024 * 1024)) + " MB). Record a shorter clip.");
        }
        if (!properties.isEnabled()) {
            return disabled("stt-disabled");
        }

        Optional<TranscriptionProvider> provider = selectProvider();
        if (provider.isEmpty()) {
            return disabled("stt-not-configured");
        }

        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw new ValidationException("The uploaded recording could not be read.");
        }

        TranscriptionResult result = provider.get().transcribe(
                bytes,
                filename(audio),
                audio.getContentType(),
                language,
                prompt);

        if (result.error() != null) {
            // Not an exception: the client shows the doctor an audible "that
            // didn't come through" and its own recogniser takes over.
            return disabled(result.error());
        }

        return TranscriptionResponse.builder()
                .text(result.text())
                .normalized(result.normalized())
                .provider(properties.getProvider())
                // The model that answered, which differs from the configured one
                // when an overloaded primary fell back.
                .model(result.model() != null ? result.model() : properties.activeModel())
                .language(result.language())
                .durationSeconds(result.durationSeconds())
                .build();
    }

    private TranscriptionResponse disabled(String error) {
        return TranscriptionResponse.builder()
                .text("")
                .provider(properties.getProvider())
                .model(properties.activeModel())
                .error(error)
                .build();
    }

    private static String filename(MultipartFile audio) {
        String original = audio.getOriginalFilename();
        if (original != null && !original.isBlank()) {
            return original;
        }
        String type = audio.getContentType();
        if (type != null && type.contains("ogg")) return "audio.ogg";
        if (type != null && type.contains("wav")) return "audio.wav";
        if (type != null && type.contains("mpeg")) return "audio.mp3";
        if (type != null && type.contains("mp4")) return "audio.mp4";
        return "audio.webm";
    }
}
