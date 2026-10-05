package com.orthoflow.messaging.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The signature scheme the bridge uses: {@code "sha256=" + hex(HMAC_SHA256(secret, "<timestamp>.<body>"))}. */
class WhatsAppWebhookSignatureTest {

    private static final String SECRET = "a-webhook-secret-of-at-least-thirty-two-chars";

    private WhatsAppWebhookService service;

    @BeforeEach
    void setUp() {
        MessagingProperties props = new MessagingProperties();
        props.getWhatsapp().setWebhookSecret(SECRET);
        service = new WhatsAppWebhookService(props, new ObjectMapper(), null, null, null, null, null, null);
    }

    private static String sign(String secret, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsABatchSignedLikeTheBridgeSignsIt() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"events\":[]}";
        assertThat(service.verify(ts, sign(SECRET, ts, body), body)).isTrue();
    }

    @Test
    void rejectsATamperedBody() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(SECRET, ts, "{\"events\":[]}");
        assertThat(service.verify(ts, signature, "{\"events\":[{\"id\":\"x\"}]}")).isFalse();
    }

    @Test
    void rejectsTheWrongSecret() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        assertThat(service.verify(ts, sign("another-secret-another-secret-another", ts, "{}"), "{}")).isFalse();
    }

    @Test
    void rejectsAStaleTimestampSoARecordedBatchCannotBeReplayed() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond() - 3600);
        assertThat(service.verify(ts, sign(SECRET, ts, "{}"), "{}")).isFalse();
    }

    @Test
    void rejectsMissingHeadersAndAnUnsetSecret() {
        assertThat(service.verify(null, null, "{}")).isFalse();
        WhatsAppWebhookService noSecret = new WhatsAppWebhookService(new MessagingProperties(), new ObjectMapper(), null, null, null, null, null, null);
        assertThat(noSecret.verify("1", "sha256=00", "{}")).isFalse();
    }
}
