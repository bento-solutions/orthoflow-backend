package com.orthoflow.messaging.presentation;

import com.orthoflow.messaging.application.service.WhatsAppWebhookService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Where the Baileys bridge posts receipts and replies. Public in
 * {@code SecurityConfig} because the caller is a service with no user — its
 * authority is the HMAC signature checked here, over the raw bytes, before the
 * body is parsed. Reachable only inside the Docker network in production.
 */
@RestController
@RequestMapping("/webhooks/whatsapp")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final WhatsAppWebhookService service;

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Map<String, List<JsonNode>>> receive(
            @RequestHeader(value = "X-Bento-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Bento-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        if (!service.verify(timestamp, signature, rawBody)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("failed", service.handle(rawBody)));
    }
}
