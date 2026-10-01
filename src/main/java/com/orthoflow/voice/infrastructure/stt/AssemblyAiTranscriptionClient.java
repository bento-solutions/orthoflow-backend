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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Speech-to-text backed by AssemblyAI's synchronous API (Universal-3.5 Pro).
 *
 * <p>The fallback when Gemini is overloaded or rate-limited. It answers a
 * short clip in well under a second, which is what a fallback in front of a
 * dentist mid-examination has to do, and it is a dedicated recogniser: it
 * returns only what it heard, never an interpretation of it.
 *
 * <p>Two settings carry the clinical context, per AssemblyAI's own guidance:
 * a short scenario {@code prompt} describing the recording, and a
 * {@code keyterms_prompt} of dental terms. The key-term list is deliberately
 * short and made of uncommon clinical words only — measured on noisy
 * consultation audio, a long list of common phrases ("needs a crown") made
 * the model emit those phrases from pure handpiece noise.
 *
 * <p>There is no {@code normalized} reading: the browser's grammar reads
 * French number words and its fuzzy repair snaps near-miss clinical terms, so
 * the verbatim transcript is enough for the command path.
 *
 * <p>The EU endpoint is the default so consultation audio stays in the EU.
 */
@Component
@Slf4j
public class AssemblyAiTranscriptionClient implements TranscriptionProvider {

    static final String MODEL = "universal-3-5-pro";

    /**
     * A description of the recording, not an instruction — the model is
     * trained on scenario descriptions. French, because the description also
     * steers the language when a custom prompt is set.
     */
    static final String SCENARIO = "Dictée clinique d'un dentiste pendant une consultation dentaire, en français, "
            + "parfois en anglais ou en darija marocaine. Le dentiste nomme les dents par leur numéro "
            + "(seize, vingt-six, trente-six, quarante et un) et décrit caries, couronnes, obturations et "
            + "l'état du parodonte. Les commandes commencent souvent par le mot Calypso.";

    /** Uncommon clinical words only; see the class comment for why the list is short. */
    static final List<String> KEY_TERMS = List.of(
            "Calypso", "carie récurrente", "carie profonde", "couronne à remplacer", "couronne défectueuse",
            "traitement canalaire", "dévitalisée", "abcès", "mobilité", "poche parodontale",
            "récession gingivale", "gingivite", "parodontite", "tartre", "détartrage", "obturation",
            "composite", "amalgame", "inlay core", "scellement de sillons", "facette", "racine résiduelle",
            "occlusale", "mésiale", "distale", "vestibulaire", "linguale", "palatine", "pénicilline",
            "antécédents médicaux", "pulpite", "nécrose pulpaire", "granulome", "avulsion", "inlay", "onlay",
            "mésio-occlusale", "occluso-distale", "anticoagulants");


    private final SpeechToTextProperties properties;
    private final VoiceProviderProperties vendors;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AssemblyAiTranscriptionClient(SpeechToTextProperties properties, VoiceProviderProperties vendors,
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
        return "assemblyai";
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey().isBlank() && !vendors.getAssemblyai().base().isBlank();
    }

    private String apiKey() {
        return vendors.keyFor(name(), properties.getProvider(), properties.getApiKey());
    }

    @Override
    public TranscriptionResult transcribe(byte[] audio, String filename, String contentType,
                                          String languageOverride, String prompt) {
        if (!isConfigured()) {
            return TranscriptionResult.ofError("stt-not-configured");
        }
        String mime = GeminiTranscriptionClient.mimeType(contentType, filename);
        if (!mime.equals("audio/wav")) {
            // The sync API decodes WAV and raw PCM only. The browser client
            // always sends WAV; anything else is an older client.
            return TranscriptionResult.ofError("stt-unsupported-format");
        }
        try {
            String boundary = "----orthoflow" + Long.toHexString(System.nanoTime());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writePart(body, boundary, "audio", "utterance.wav", "audio/wav", audio);
            writePart(body, boundary, "config", null, "application/json",
                    objectMapper.writeValueAsBytes(config(prompt)));
            body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(vendors.getAssemblyai().base() + "/transcribe"))
                    .timeout(Duration.ofMillis(properties.getFallbackTimeoutMs()))
                    // Raw key, no "Bearer" — AssemblyAI's convention.
                    .header("Authorization", apiKey())
                    .header("X-AAI-Model", MODEL)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("AssemblyAI speech-to-text returned HTTP {} — body: {}",
                        response.statusCode(), truncate(response.body()));
                return TranscriptionResult.ofError("stt-http-" + response.statusCode());
            }
            return parse(objectMapper.readTree(response.body()), languageOverride);

        } catch (HttpTimeoutException e) {
            log.warn("AssemblyAI speech-to-text timed out after {} ms", properties.getFallbackTimeoutMs());
            return TranscriptionResult.ofError("stt-timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TranscriptionResult.ofError("stt-interrupted");
        } catch (Exception e) {
            log.warn("AssemblyAI speech-to-text call failed: {}", e.toString());
            return TranscriptionResult.ofError("stt-request-failed");
        }
    }

    Map<String, Object> config(String hint) {
        Map<String, Object> config = new LinkedHashMap<>();
        // The scenario is fixed text. The patient's name used to be lifted from
        // the browser's hint and appended here; it is personal data and helps
        // nothing a dictated tooth or finding needs, so it is never sent.
        config.put("prompt", SCENARIO);
        config.put("keyterms_prompt", KEY_TERMS);
        return config;
    }


    TranscriptionResult parse(JsonNode root, String language) {
        String text = root.path("text").asText("").trim();
        Double duration = root.hasNonNull("audio_duration_ms")
                ? root.get("audio_duration_ms").asDouble() / 1000.0
                : null;
        if (text.isEmpty()) {
            return TranscriptionResult.ofText("", null, language, duration, MODEL);
        }
        // A recogniser biased towards clinical words, fed handpiece noise, can
        // produce a fluent clinical-sounding sentence at very low confidence.
        // Dropping it is safe: the dentist hears nothing and repeats, whereas
        // a hallucinated finding would be staged and read back.
        double confidence = root.path("confidence").asDouble(1.0);
        if (confidence < properties.getMinConfidence()) {
            log.info("AssemblyAI transcript dropped at confidence {} (floor {})",
                    String.format(Locale.ROOT, "%.2f", confidence), properties.getMinConfidence());
            return TranscriptionResult.ofText("", null, language, duration, MODEL);
        }
        return TranscriptionResult.ofText(text, null, language, duration, MODEL);
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String filename,
                                  String contentType, byte[] content) {
        StringBuilder header = new StringBuilder("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append('"');
        if (filename != null) {
            header.append("; filename=\"").append(filename).append('"');
        }
        header.append("\r\nContent-Type: ").append(contentType).append("\r\n\r\n");
        out.writeBytes(header.toString().getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
