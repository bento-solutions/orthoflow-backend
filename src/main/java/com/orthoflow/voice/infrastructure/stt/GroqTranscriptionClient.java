package com.orthoflow.voice.infrastructure.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speech-to-text backed by any server that implements the OpenAI
 * {@code POST /audio/transcriptions} shape — Groq's hosted Whisper, or a
 * self-hosted {@code faster-whisper} / {@code whisper.cpp} server.
 *
 * <p>Raw {@link HttpClient} and a hand-built {@code multipart/form-data} body
 * rather than a vendor SDK, for the same reason {@link
 * com.orthoflow.voice.infrastructure.nlu.OpenAiCompatibleNluProvider} does it:
 * the multipart transcription shape is the one thing every Whisper host
 * implements, and not depending on a vendor client keeps the provider
 * swappable by configuration alone.
 *
 * <h2>Whisper hallucinates, and in a surgery that matters</h2>
 *
 * <p>Measured on consultation audio with handpiece and suction noise, Whisper
 * returned "Merci." for pure noise and — worse — recited its own prompt back
 * as though the dentist had dictated it: "abcès, mobilité, poche parodontale,
 * récession gingivale…". So this client:
 *
 * <ul>
 *   <li>sends its own short prompt — Whisper only reads the last 224 tokens,
 *       and the browser's vocabulary list is far longer — rather than the
 *       browser's hint;</li>
 *   <li>drops segments Whisper itself scores as probably not speech;</li>
 *   <li>drops a transcript that is a recital of the prompt's terms with no
 *       tooth in it.</li>
 * </ul>
 *
 * <p>An upstream failure comes back as {@link TranscriptionResult#ofError} —
 * never a thrown exception — so the caller moves on to the next provider.
 */
@Component
@Slf4j
public class GroqTranscriptionClient implements TranscriptionProvider {

    /** The terms Whisper is primed with; also what a prompt echo is recognised by. */
    static final List<String> PROMPT_TERMS = List.of(
            "carie récurrente", "carie profonde", "couronne à remplacer", "traitement canalaire", "abcès",
            "mobilité", "poche parodontale", "récession gingivale", "gingivite", "tartre", "obturation",
            "composite", "occlusale", "mésiale", "distale");

    static final String PROMPT = "Calypso, dent seize. Vocabulaire dentaire : " + String.join(", ", PROMPT_TERMS) + ".";

    /** Whisper's own estimate that a segment holds no speech, above which it is dropped. */
    static final double NO_SPEECH_CEILING = 0.6;

    /** Mean token log-probability below which a segment is a guess, not a transcript. */
    static final double MIN_AVG_LOGPROB = -1.0;

    private final SpeechToTextProperties properties;
    private final VoiceProviderProperties vendors;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GroqTranscriptionClient(SpeechToTextProperties properties, VoiceProviderProperties vendors,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.vendors = vendors;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "groq";
    }

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public boolean isConfigured() {
        return !apiKey().isBlank() && !baseUrl().isBlank();
    }

    private String apiKey() {
        return vendors.keyFor(name(), properties.getProvider(), properties.getApiKey());
    }

    /**
     * {@code orthoflow.voice.stt.base-url} still wins, so a deployment pointed
     * at a self-hosted Whisper keeps working.
     */
    private String baseUrl() {
        String own = properties.getBaseUrl();
        return own != null && !own.isBlank() ? own.replaceAll("/+$", "") : vendors.getGroq().base();
    }

    /**
     * @param languageOverride ISO-639-1 hint, or null/blank to auto-detect
     * @param prompt           the browser's vocabulary hint — not forwarded;
     *                         see the class comment
     */
    @Override
    public TranscriptionResult transcribe(byte[] audio, String filename, String contentType,
                                          String languageOverride, String prompt) {
        if (!isConfigured()) {
            return TranscriptionResult.ofError("stt-not-configured");
        }
        try {
            String boundary = "----orthoflow" + Long.toHexString(System.nanoTime());
            List<byte[]> parts = new ArrayList<>();

            parts.add(field(boundary, "model", properties.getModel()));
            // verbose_json is what carries per-segment no-speech probabilities,
            // the detected language and the clip duration.
            parts.add(field(boundary, "response_format", "verbose_json"));
            parts.add(field(boundary, "temperature", Double.toString(properties.getTemperature())));

            String language = languageOverride != null && !languageOverride.isBlank()
                    ? languageOverride.trim()
                    : properties.getLanguage();
            if (language != null && !language.isBlank()) {
                parts.add(field(boundary, "language", language.trim()));
            }
            parts.add(field(boundary, "prompt", PROMPT));
            parts.add(filePart(boundary, "file", filename, contentType, audio));
            parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/audio/transcriptions"))
                    .timeout(Duration.ofMillis(properties.getFallbackTimeoutMs()))
                    .header("Authorization", "Bearer " + apiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(concat(parts)))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                log.warn("Whisper speech-to-text returned HTTP {} — body: {}",
                        response.statusCode(), truncate(response.body()));
                return TranscriptionResult.ofError("stt-http-" + response.statusCode());
            }
            return parse(objectMapper.readTree(response.body()));

        } catch (HttpTimeoutException e) {
            log.warn("Whisper speech-to-text timed out after {} ms", properties.getFallbackTimeoutMs());
            return TranscriptionResult.ofError("stt-timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TranscriptionResult.ofError("stt-interrupted");
        } catch (Exception e) {
            log.warn("Whisper speech-to-text call failed: {}", e.toString());
            return TranscriptionResult.ofError("stt-request-failed");
        }
    }

    TranscriptionResult parse(JsonNode root) {
        String detected = root.hasNonNull("language") ? root.get("language").asText() : null;
        Double duration = root.hasNonNull("duration") ? root.get("duration").asDouble() : null;

        String text;
        JsonNode segments = root.path("segments");
        if (segments.isArray() && !segments.isEmpty()) {
            StringBuilder kept = new StringBuilder();
            for (JsonNode segment : segments) {
                if (segment.path("no_speech_prob").asDouble(0) > NO_SPEECH_CEILING
                        || segment.path("avg_logprob").asDouble(0) < MIN_AVG_LOGPROB) {
                    continue;
                }
                kept.append(segment.path("text").asText("")).append(' ');
            }
            text = kept.toString().trim();
        } else {
            text = root.path("text").asText("").trim();
        }

        if (isPromptEcho(text)) {
            log.info("Whisper recited its prompt instead of transcribing; dropped ({} chars)", text.length());
            text = "";
        }
        return TranscriptionResult.ofText(text, null, detected, duration, properties.getModel());
    }

    /**
     * Four or more of the prompt's terms and nothing that names a tooth. A
     * real dictation naming that many findings names the tooth too; a recital
     * of the prompt never does.
     */
    static boolean isPromptEcho(String text) {
        if (text == null || text.isBlank()) return false;
        String folded = fold(text);
        long terms = PROMPT_TERMS.stream().filter(term -> folded.contains(fold(term))).count();
        if (terms < 4) return false;
        boolean namesTooth = folded.matches("(?s).*\\d.*")
                || folded.matches("(?s).*\\b(onze|douze|treize|quatorze|quinze|seize|dix|vingt|trente|quarante"
                        + "|cinquante|soixante|quatre-vingt|eleven|twelve|thirteen|fourteen|fifteen|sixteen"
                        + "|seventeen|eighteen|twenty|thirty|forty)\\b.*");
        return !namesTooth;
    }

    private static String fold(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    // ── multipart/form-data assembly ────────────────────────────────────

    private static byte[] field(String boundary, String name, String value) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        return header.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] filePart(String boundary, String name, String filename,
                                   String contentType, byte[] content) {
        String safeName = filename == null || filename.isBlank() ? "audio.webm" : filename;
        String mime = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + safeName + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
