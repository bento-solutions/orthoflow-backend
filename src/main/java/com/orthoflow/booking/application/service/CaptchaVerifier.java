package com.orthoflow.booking.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.booking.infrastructure.BookingProperties;
import com.orthoflow.common.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifies the CAPTCHA challenge on public booking submissions (Cloudflare Turnstile
 * compatible). Skipped in environments where {@code orthoflow.booking.captcha-enabled}
 * is false, or when a trusted server-to-server caller presents the configured API key.
 */
@Service
public class CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(CaptchaVerifier.class);

    private final BookingProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public CaptchaVerifier(BookingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean matchesApiKey(String presentedKey) {
        String expected = properties.getApiKey();
        if (expected == null || expected.isBlank() || presentedKey == null || presentedKey.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                presentedKey.trim().getBytes(StandardCharsets.UTF_8));
    }

    public void verify(String captchaToken, String presentedApiKey, String clientIp) {
        if (matchesApiKey(presentedApiKey)) {
            return;
        }
        if (!properties.isCaptchaEnabled()) {
            return;
        }
        String secret = properties.getCaptchaSecretKey();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("BOOKING_CAPTCHA_ENABLED is true but BOOKING_CAPTCHA_SECRET_KEY is empty");
        }
        if (captchaToken == null || captchaToken.isBlank()) {
            throw new ValidationException("Please complete the security verification.");
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", secret);
            form.add("response", captchaToken.trim());
            if (clientIp != null && !clientIp.isBlank()) {
                form.add("remoteip", clientIp);
            }
            String raw = client.post()
                    .uri(properties.getCaptchaVerifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode json = raw == null ? null : objectMapper.readTree(raw);
            if (json == null || !json.path("success").asBoolean(false)) {
                throw new ValidationException("Security verification failed. Please try again.");
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("CAPTCHA verification request failed: {}", e.getMessage());
            throw new ValidationException("Unable to verify security check right now. Please try again.");
        }
    }
}
