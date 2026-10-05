package com.orthoflow.messaging.infrastructure.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.messaging.application.port.ChannelSender;
import com.orthoflow.messaging.application.port.WhatsAppProvider;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;

/**
 * Talks to the self-hosted Baileys bridge — the same service the CRM uses, a
 * second instance of it for this clinic:
 *
 * <pre>POST {base}/sessions/{id}/messages   Authorization: Bearer {key}
 *   {"messageId": "...", "to": "212661234567", "text": "..."}</pre>
 *
 * The bridge repeats a stored result for a repeated {@code messageId}, so the
 * outbox id is sent as that id and a retry after a lost response cannot send
 * the message twice. Failures carry {@code retryable}, which decides whether
 * the outbox tries again.
 */
@Component
public class BaileysWhatsAppProvider implements WhatsAppProvider {

    private static final Logger log = LoggerFactory.getLogger(BaileysWhatsAppProvider.class);

    private final MessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public BaileysWhatsAppProvider(MessagingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getWhatsapp().getTimeoutMs());
        factory.setReadTimeout(properties.getWhatsapp().getTimeoutMs());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean enabled() {
        MessagingProperties.WhatsApp wa = properties.getWhatsapp();
        return wa.isEnabled() && !wa.getApiKey().isBlank() && !wa.getBaseUrl().isBlank();
    }

    @Override
    public ChannelSender.Result send(String messageId, String toDigits, String text) {
        MessagingProperties.WhatsApp wa = properties.getWhatsapp();
        try {
            JsonNode body = client.post()
                    .uri(wa.getBaseUrl() + "/sessions/{id}/messages", wa.getSessionId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + wa.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messageId", messageId, "to", toDigits, "text", text))
                    .retrieve()
                    .body(JsonNode.class);
            return ChannelSender.Result.sent(body != null && body.hasNonNull("wamid") ? body.get("wamid").asText() : messageId);
        } catch (RestClientResponseException e) {
            return failure(e);
        } catch (RuntimeException e) {
            // Connection refused, timeout: the bridge is down or restarting.
            log.warn("WhatsApp bridge unreachable: {}", e.getMessage());
            return ChannelSender.Result.retry("WhatsApp bridge unreachable: " + e.getMessage(), 0);
        }
    }

    @Override
    public Optional<String> sessionState() {
        if (!enabled()) {
            return Optional.empty();
        }
        MessagingProperties.WhatsApp wa = properties.getWhatsapp();
        try {
            JsonNode body = client.get()
                    .uri(wa.getBaseUrl() + "/sessions/{id}", wa.getSessionId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + wa.getApiKey())
                    .retrieve().body(JsonNode.class);
            return body != null && body.hasNonNull("state") ? Optional.of(body.get("state").asText()) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.of("unreachable");
        }
    }

    private ChannelSender.Result failure(RestClientResponseException e) {
        String code = null;
        String message = e.getStatusText();
        boolean retryable = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429 || e.getStatusCode().value() == 409;
        long retryAfter = 0;
        try {
            JsonNode json = objectMapper.readTree(e.getResponseBodyAsString());
            code = json.path("code").asText(null);
            message = json.path("message").asText(message);
            if (json.has("retryable")) {
                retryable = json.get("retryable").asBoolean();
            }
            retryAfter = json.path("retryAfterMs").asLong(0);
        } catch (Exception ignored) {
            // Not JSON (a proxy error page): fall back to the status-based guess above.
        }
        String error = (code != null ? code + ": " : "") + message;
        return retryable ? ChannelSender.Result.retry(error, retryAfter) : ChannelSender.Result.fail(error);
    }
}
