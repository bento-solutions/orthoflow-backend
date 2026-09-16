package com.orthoflow.voice.infrastructure.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.voice.application.dto.InterpretRequest;
import com.orthoflow.voice.infrastructure.stt.SpeechToTextProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Natural-language fallback backed by Gemini's Interactions API.
 *
 * <p>This uses the same Google Generative Language API as the Gemini
 * speech-to-text client, but for text interpretation rather than audio
 * transcription. The API key is shared with STT unless a separate one is
 * configured under {@code orthoflow.voice.nlu.gemini-api-key}.
 *
 * <p>Gemini is a strong choice here because mapping a short clinical utterance
 * onto a listed intent is a classification task, and Gemini Flash handles it
 * with low latency and solid multilingual support — particularly for the
 * French-with-Darija code-switching this app encounters.
 *
 * <p>The same data-protection considerations from {@link AnthropicNluProvider}
 * apply: utterances are sent to a cloud provider. The deployer needs a DPA,
 * a lawful basis and a CNDP notification under Law 09-08 first. For clinics
 * that cannot make that arrangement, {@link OpenAiCompatibleNluProvider}
 * pointed at a local model keeps data on-premise.
 */
@Component
@Slf4j
public class GeminiNluProvider implements NluProvider {

    private final VoiceNluProperties properties;
    private final SpeechToTextProperties sttProperties;
    private final NluPromptBuilder promptBuilder;
    private final NluResponseParser responseParser;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiNluProvider(VoiceNluProperties properties,
                             SpeechToTextProperties sttProperties,
                             NluPromptBuilder promptBuilder,
                             NluResponseParser responseParser,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.sttProperties = sttProperties;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getNlu().getTimeoutMs()))
                .build();
    }

    @Override
    public String name() {
        return "gemini";
    }

    @Override
    public boolean isAvailable() {
        return resolveApiKey() != null && !resolveApiKey().isBlank()
                && resolveBaseUrl() != null && !resolveBaseUrl().isBlank();
    }

    @Override
    public NluInterpretation interpret(InterpretRequest request) {
        if (!isAvailable()) {
            return NluInterpretation.unavailable(name(),
                    "Gemini NLU selected but API key or base URL is not set. "
                    + "Set orthoflow.voice.nlu.gemini-api-key or orthoflow.voice.stt.api-key.");
        }
        try {
            List<Map<String, Object>> input = new ArrayList<>();
            input.add(Map.of("type", "text", "text", promptBuilder.userPrompt(request)));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", resolveModel());
            body.put("system_instruction", promptBuilder.systemPrompt(request));
            body.put("input", input);
            // The Interactions API takes these under generation_config and
            // response_format. Sent at the top level (thinking_level,
            // output_config) they are rejected as unknown parameters, which
            // failed every interpretation with HTTP 400.
            Map<String, Object> generation = new LinkedHashMap<>();
            generation.put("max_output_tokens", properties.getNlu().getMaxOutputTokens());
            String thinking = properties.getNlu().getGeminiThinkingLevel();
            if (thinking != null && !thinking.isBlank()) {
                generation.put("thinking_level", thinking.trim());
            }
            body.put("generation_config", generation);
            body.put("response_format", Map.of(
                    "type", "text",
                    "mime_type", "application/json",
                    "schema", promptBuilder.responseSchema()));
            // Not persisted on Google's side — consultation data.
            body.put("store", false);

            String base = resolveBaseUrl().replaceAll("/+$", "");
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/interactions"))
                    .timeout(Duration.ofMillis(properties.getNlu().getTimeoutMs()))
                    .header("x-goog-api-key", resolveApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                log.warn("Gemini NLU returned HTTP {} — body: {}",
                        response.statusCode(), truncate(response.body()));
                return NluInterpretation.unavailable(name(), "NLU HTTP " + response.statusCode());
            }

            String text = extractText(objectMapper.readTree(response.body()));
            return responseParser.parse(text, request, name());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NluInterpretation.unavailable(name(), "NLU request interrupted");
        } catch (Exception e) {
            log.warn("Gemini NLU call failed: {}", e.toString());
            return NluInterpretation.unavailable(name(), "NLU request failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * The transcript lives in the first {@code model_output} step's text
     * content — same response shape as Gemini STT.
     */
    private static String extractText(JsonNode root) {
        StringBuilder out = new StringBuilder();
        for (JsonNode step : root.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) {
                continue;
            }
            for (JsonNode part : step.path("content")) {
                if ("text".equals(part.path("type").asText())) {
                    out.append(part.path("text").asText(""));
                }
            }
            if (!out.isEmpty()) {
                break;
            }
        }
        return out.toString().trim();
    }

    /**
     * Falls back to the STT API key when no NLU-specific key is set, so a
     * single Gemini key in .env drives both STT and NLU.
     */
    private String resolveApiKey() {
        String nluKey = properties.getNlu().getGeminiApiKey();
        if (nluKey != null && !nluKey.isBlank()) {
            return nluKey;
        }
        // Fall back to the shared STT key — common when one Gemini key covers everything.
        return sttProperties.getApiKey();
    }

    private String resolveBaseUrl() {
        String nluUrl = properties.getNlu().getGeminiBaseUrl();
        if (nluUrl != null && !nluUrl.isBlank()) {
            return nluUrl;
        }
        return "https://generativelanguage.googleapis.com/v1beta";
    }

    private String resolveModel() {
        String model = properties.getNlu().getGeminiModel();
        return (model != null && !model.isBlank()) ? model : "gemini-3.5-flash";
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
