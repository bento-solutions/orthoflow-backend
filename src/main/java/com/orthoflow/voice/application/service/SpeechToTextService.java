package com.orthoflow.voice.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.voice.application.dto.TranscriptionResponse;
import com.orthoflow.voice.infrastructure.stt.SpeechToTextProperties;
import com.orthoflow.voice.infrastructure.stt.TranscriptionProvider;
import com.orthoflow.voice.infrastructure.stt.TranscriptionResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Server-side capture-to-text. The browser records a clip and posts it here;
 * this hands back a transcript that re-enters the client pipeline exactly
 * where a browser-recognised utterance would.
 *
 * <h2>A chain, not a provider</h2>
 *
 * <p>The configured {@code provider} is tried first and each of
 * {@code fallbacks} after it, inside one time budget. The reason is measured,
 * not theoretical: the Gemini key a clinic starts on answers 429 after a
 * handful of clips a minute and 503 "high demand" at busy hours, and a clip
 * that fails is a finding the dentist has to say again with their hands in a
 * patient's mouth. A provider that just failed on overload is skipped for a
 * short while rather than costing every following clip a doomed round trip.
 *
 * <p>Transcription off, or no provider configured, returns a transcript-less
 * {@link TranscriptionResponse} carrying an {@code error} tag rather than
 * throwing, so the client falls back to its own recogniser. Only a malformed
 * request (no audio, or a clip past the configured ceiling) is a hard 400.
 */
@Service
@Slf4j
public class SpeechToTextService {

    /** How long a provider that answered 429/503/timeout is skipped. */
    static final long OVERLOAD_COOLDOWN_MS = 20_000;

    /** A provider is not worth starting with less time than this left. */
    private static final long MIN_ATTEMPT_BUDGET_MS = 1_500;

    private static final Set<String> OVERLOAD_ERRORS =
            Set.of("stt-http-429", "stt-http-503", "stt-http-500", "stt-http-502", "stt-http-504", "stt-timeout");

    /**
     * What recognisers say about silence and noise. Every one of these was
     * trained on subtitled video, and the subtitle credits come out when
     * there is nothing to transcribe. None of them is ever a dental command —
     * "ok" is deliberately absent, since it answers a confirmation.
     */
    private static final Set<String> SILENCE_HALLUCINATIONS = Set.of(
            "merci", "merci beaucoup", "merci d'avoir regarde", "merci de votre attention", "au revoir",
            "sous-titrage st' 501", "sous-titres realises par la communaute d'amara.org",
            "sous-titres realises para la communaute d'amara.org", "abonnez-vous", "musique",
            "thank you", "thanks for watching", "thank you for watching", "you", "bye", "hmm", "euh", "...", "[music]", "[musique]", "(musique)", "[silence]", "[bruit]");

    private final SpeechToTextProperties properties;
    private final List<TranscriptionProvider> providers;
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    @Autowired
    public SpeechToTextService(SpeechToTextProperties properties, List<TranscriptionProvider> providers) {
        this(properties, providers, System::currentTimeMillis);
    }

    SpeechToTextService(SpeechToTextProperties properties, List<TranscriptionProvider> providers,
                        LongSupplier clock) {
        this.properties = properties;
        this.providers = providers;
        this.clock = clock;
    }

    private Optional<TranscriptionProvider> find(String name) {
        return providers.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * The primary then each fallback, configured ones only, each once.
     *
     * <p>An unrecognised primary name empties the chain rather than silently
     * promoting a fallback: a deployment that meant to send audio to Gemini
     * and typoed the name should get browser recognition and a warning in
     * the log, not audio sent to a vendor nobody chose as primary.
     */
    List<TranscriptionProvider> chain() {
        Optional<TranscriptionProvider> primary = find(properties.getProvider());
        if (primary.isEmpty()) {
            return List.of();
        }
        List<TranscriptionProvider> chain = new ArrayList<>();
        chain.add(primary.get());
        for (String name : properties.getFallbacks() == null ? List.<String>of() : properties.getFallbacks()) {
            find(name.trim())
                    .filter(p -> !chain.contains(p))
                    .ifPresent(chain::add);
        }
        return chain.stream().filter(TranscriptionProvider::isConfigured).toList();
    }

    @PostConstruct
    void reportConfiguration() {
        if (!properties.isEnabled()) {
            log.info("Server-side speech-to-text disabled. The browser's own SpeechRecognition is used; "
                    + "set orthoflow.voice.stt.enabled=true with a provider and API key to route capture "
                    + "through a hosted recogniser instead.");
            return;
        }
        if (find(properties.getProvider()).isEmpty()) {
            log.warn("orthoflow.voice.stt.provider='{}' matches no registered provider (known: {}) — "
                            + "/voice/transcribe will report stt-not-configured and the client will fall "
                            + "back to browser recognition.",
                    properties.getProvider(), providers.stream().map(TranscriptionProvider::name).toList());
            return;
        }
        List<TranscriptionProvider> chain = chain();
        if (chain.isEmpty()) {
            log.warn("orthoflow.voice.stt.enabled=true but no provider in the chain has an API key — "
                    + "/voice/transcribe will report stt-not-configured.");
        } else {
            log.info("Server-side speech-to-text enabled: chain={}",
                    chain.stream().map(p -> p.name() + "(" + p.model() + ")").toList());
            if (!chain.get(0).name().equalsIgnoreCase(properties.getProvider())) {
                log.warn("Primary speech-to-text provider '{}' has no API key; '{}' is answering instead.",
                        properties.getProvider(), chain.get(0).name());
            }
        }
    }

    /**
     * @param audio    the recorded clip (the browser sends 16 kHz WAV)
     * @param language ISO-639-1 hint, or null/blank to let the provider detect
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
            return failed("stt-disabled");
        }

        List<TranscriptionProvider> chain = chain();
        if (chain.isEmpty()) {
            return failed("stt-not-configured");
        }

        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw new ValidationException("The uploaded recording could not be read.");
        }

        long deadline = clock.getAsLong() + properties.getTotalBudgetMs();
        String lastError = null;
        for (int i = 0; i < chain.size(); i++) {
            TranscriptionProvider provider = chain.get(i);
            boolean last = i == chain.size() - 1;
            // A cooling provider is skipped — unless it is all that is left,
            // in which case trying beats reporting a failure unasked.
            if (!last && isCooling(provider)) {
                continue;
            }
            if (clock.getAsLong() + MIN_ATTEMPT_BUDGET_MS > deadline) {
                break;
            }

            TranscriptionResult result = provider.transcribe(bytes, filename(audio), audio.getContentType(),
                    language, prompt);
            if (result == null) {
                result = TranscriptionResult.ofError("stt-request-failed");
            }
            if (result.error() == null) {
                cooldownUntil.remove(provider.name());
                if (i > 0) {
                    log.info("Speech-to-text answered by fallback '{}' ({})", provider.name(),
                            lastError != null ? "after " + lastError : "earlier providers cooling down");
                }
                return success(provider, result);
            }

            lastError = result.error();
            if (OVERLOAD_ERRORS.contains(lastError)) {
                cooldownUntil.put(provider.name(), clock.getAsLong() + OVERLOAD_COOLDOWN_MS);
            }
            log.warn("Speech-to-text provider '{}' failed ({}){}", provider.name(), lastError,
                    last ? "" : " — trying the next provider");
        }
        // Not an exception: the client tells the doctor, audibly, that the
        // clip did not come through.
        return failed(lastError != null ? lastError : "stt-unavailable");
    }

    private boolean isCooling(TranscriptionProvider provider) {
        Long until = cooldownUntil.get(provider.name());
        return until != null && clock.getAsLong() < until;
    }

    private TranscriptionResponse success(TranscriptionProvider provider, TranscriptionResult result) {
        boolean noise = isSilenceHallucination(result.text());
        return TranscriptionResponse.builder()
                .text(noise ? "" : result.text())
                .normalized(noise ? null : result.normalized())
                .provider(provider.name())
                // The model that answered, which differs from the configured
                // one when an overloaded primary fell back.
                .model(result.model() != null ? result.model() : provider.model())
                .language(result.language())
                .durationSeconds(result.durationSeconds())
                .build();
    }

    /** True for what a recogniser produces from silence rather than speech. */
    static boolean isSilenceHallucination(String text) {
        if (text == null || text.isBlank()) return false;
        String folded = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('’', '\'')
                .replaceAll("[\\s]+", " ")
                .replaceAll("^[\\s.!?,…]+|[\\s.!?,…]+$", "")
                .trim();
        return folded.isEmpty() || SILENCE_HALLUCINATIONS.contains(folded);
    }

    private TranscriptionResponse failed(String error) {
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
