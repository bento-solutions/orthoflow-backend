package com.orthoflow.voice.infrastructure.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One call to an OpenAI-compatible {@code /chat/completions} endpoint — Groq
 * and DeepSeek both speak it — for the stages that fall back between vendors.
 *
 * <p>Raw {@link HttpClient} rather than a vendor SDK, like the rest of the
 * voice pipeline: the chat-completions shape is the one thing every host
 * implements, and a fallback chain is only worth having if switching vendor
 * is configuration rather than code.
 *
 * <p>Never throws for an upstream problem. A {@link Result} carrying an
 * {@code error} tells the caller to try the next vendor.
 */
@Component
@Slf4j
public class ChatCompletionClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ChatCompletionClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * @param error    null on success; otherwise a short tag ({@code http-429},
     *                 {@code timeout}, {@code empty}, {@code failed})
     */
    public record Result(String text, String error) {
        static Result ok(String text) {
            return new Result(text, null);
        }

        static Result failed(String error) {
            return new Result(null, error);
        }

        public boolean succeeded() {
            return error == null;
        }
    }

    public record Request(String baseUrl, String apiKey, String model, String system, String user,
                          int maxTokens, boolean json, long timeoutMs) {}

    public Result complete(Request request) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", request.model());
            body.put("max_tokens", request.maxTokens());
            // A clinical answer should not vary between two runs on the same input.
            body.put("temperature", 0);
            if (request.json()) {
                body.put("response_format", Map.of("type", "json_object"));
            }
            body.put("messages", List.of(
                    Map.of("role", "system", "content", request.system()),
                    Map.of("role", "user", "content", request.user())));

            HttpRequest http = HttpRequest.newBuilder()
                    .uri(URI.create(request.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + request.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Chat completion ({} @ {}) returned HTTP {} — body: {}", request.model(),
                        URI.create(request.baseUrl()).getHost(), response.statusCode(), truncate(response.body()));
                return Result.failed("http-" + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            // Reasoning models on some hosts inline their thinking; it is never
            // part of the answer.
            text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
            return text.isBlank() ? Result.failed("empty") : Result.ok(text);

        } catch (HttpTimeoutException e) {
            log.warn("Chat completion ({}) timed out after {} ms", request.model(), request.timeoutMs());
            return Result.failed("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("interrupted");
        } catch (Exception e) {
            log.warn("Chat completion ({}) failed: {}", request.model(), e.toString());
            return Result.failed("failed");
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
