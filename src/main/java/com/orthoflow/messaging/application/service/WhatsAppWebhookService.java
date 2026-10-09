package com.orthoflow.messaging.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.messaging.application.dto.MessagingDtos.InboxRow;
import com.orthoflow.messaging.application.dto.MessagingDtos.WhatsAppStatus;
import com.orthoflow.messaging.application.port.InboundMessageListener;
import com.orthoflow.messaging.application.port.WhatsAppProvider;
import com.orthoflow.messaging.domain.model.MessageEvent;
import com.orthoflow.messaging.domain.model.MessageStatus;
import com.orthoflow.messaging.domain.model.OutboxMessage;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import com.orthoflow.messaging.infrastructure.persistence.MessageEventJpaRepository;
import com.orthoflow.messaging.infrastructure.persistence.OutboxJpaRepository;
import com.orthoflow.patient.application.port.PatientLookup;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the Baileys bridge reports back: delivery receipts, patients' replies
 * and the linked device's state. The bridge delivers at least once and signs
 * each batch ({@code X-Bento-Signature = sha256(HMAC(secret, "<timestamp>.<body>"))}),
 * so a batch is authenticated before it is read and each event is applied
 * idempotently.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppWebhookService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookService.class);
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private record Reported(String state, OffsetDateTime at) {
    }

    private final MessagingProperties properties;
    private final ObjectMapper objectMapper;
    private final OutboxJpaRepository outbox;
    private final MessageEventJpaRepository events;
    private final PatientLookup patientLookup;
    private final WhatsAppProvider provider;
    private final LiveEventPublisher liveEvents;
    private final ObjectProvider<InboundMessageListener> listeners;
    private final AtomicReference<Reported> lastSessionReport = new AtomicReference<>();

    /** Constant-time check of the bridge's signature, rejecting a stale timestamp (replay). */
    public boolean verify(String timestamp, String signature, String rawBody) {
        String secret = properties.getWhatsapp().getWebhookSecret();
        if (secret == null || secret.isBlank() || timestamp == null || signature == null) {
            return false;
        }
        try {
            if (Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > MAX_CLOCK_SKEW_SECONDS) {
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** Applies a batch; returns the ids of the events that failed so the bridge retries just those. */
    @Transactional
    public List<String> handle(String rawBody) {
        List<String> failed = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return failed;
        }
        for (JsonNode event : root.path("events")) {
            String id = event.path("id").asText();
            try {
                apply(event.path("type").asText(), event.path("data"));
            } catch (RuntimeException e) {
                log.warn("Could not apply bridge event {}: {}", id, e.getMessage());
                failed.add(id);
            }
        }
        return failed;
    }

    private void apply(String type, JsonNode data) {
        switch (type) {
            case "message.status" -> applyReceipt(data);
            case "message.upsert" -> applyInbound(data);
            case "session.status" -> lastSessionReport.set(new Reported(data.path("state").asText("unknown"), OffsetDateTime.now()));
            default -> log.debug("Ignoring bridge event of type {}", type);
        }
    }

    private void applyReceipt(JsonNode data) {
        String wamid = data.path("wamid").asText(null);
        if (wamid == null) {
            return;
        }
        OutboxMessage message = outbox.findByProviderMessageId(wamid).orElse(null);
        if (message == null) {
            return;
        }
        String status = data.path("status").asText("").toUpperCase();
        MessageStatus target = switch (status) {
            case "READ", "PLAYED" -> MessageStatus.READ;
            case "DELIVERED", "DELIVERY_ACK" -> MessageStatus.DELIVERED;
            case "FAILED", "ERROR" -> MessageStatus.FAILED;
            default -> null;
        };
        if (target == null || (target != MessageStatus.FAILED && rank(target) <= rank(message.getStatus()))) {
            return;
        }
        if (target == MessageStatus.FAILED && message.getStatus().isDelivered() && message.getStatus() != MessageStatus.SENT) {
            return;
        }
        message.setStatus(target);
        if (target == MessageStatus.READ) {
            message.setReadAt(OffsetDateTime.now());
        }
        if (target == MessageStatus.FAILED) {
            message.setLastError(data.path("errorTitle").asText(data.path("errorCode").asText("WhatsApp could not deliver the message")));
        }
        events.save(MessageEvent.builder().practiceId(message.getPracticeId()).outboxId(message.getId())
                .direction("OUT").eventType(target.name()).externalId(wamid + ":" + target.name()).build());
    }

    private void applyInbound(JsonNode data) {
        if (!"IN".equals(data.path("direction").asText())) {
            return;
        }
        // History sync replays old conversations; only what arrives live (or while the bridge was offline) is a reply.
        if ("history".equals(data.path("origin").asText())) {
            return;
        }
        String wamid = data.path("wamid").asText(null);
        if (wamid != null && events.existsByDirectionAndEventTypeAndExternalId("IN", "MESSAGE", wamid)) {
            return;
        }
        String phone = data.path("phoneE164").asText(null);
        String digits = phone == null ? null : phone.replaceAll("[^0-9]", "");
        String body = data.path("body").asText("");
        UUID practiceId = properties.getWhatsapp().getPracticeId();
        UUID patientId = digits == null ? null : patientLookup.findIdByPhoneDigits(practiceId, digits).orElse(null);

        MessageEvent saved = events.save(MessageEvent.builder().practiceId(practiceId).direction("IN").eventType("MESSAGE")
                .externalId(wamid).fromPhone(digits).body(body).patientId(patientId).build());

        boolean handled = false;
        if (patientId != null) {
            for (InboundMessageListener listener : (Iterable<InboundMessageListener>) listeners.orderedStream()::iterator) {
                if (listener.onInbound(practiceId, patientId, digits, body)) {
                    handled = true;
                    break;
                }
            }
        }
        if (handled) {
            saved.setHandledAt(OffsetDateTime.now());
        } else {
            liveEvents.publish(practiceId, "whatsapp-inbox", saved.getId());
        }
    }

    @Transactional(readOnly = true)
    public List<InboxRow> inbox(UUID practiceId, boolean unhandledOnly, int limit) {
        List<MessageEvent> rows = events.inbox(practiceId, unhandledOnly, PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
        Map<UUID, com.orthoflow.patient.application.port.PatientSummary> patients = patientLookup.findSummaries(
                rows.stream().map(MessageEvent::getPatientId).filter(java.util.Objects::nonNull).distinct().toList());
        return rows.stream().map(e -> new InboxRow(e.getId(), e.getFromPhone(), e.getBody(), e.getPatientId(),
                e.getPatientId() == null || patients.get(e.getPatientId()) == null ? null : patients.get(e.getPatientId()).fullName(),
                e.getOccurredAt(), e.getHandledAt())).toList();
    }

    @Transactional
    public void markHandled(UUID practiceId, UUID eventId) {
        events.findById(eventId).filter(e -> e.getPracticeId().equals(practiceId)).ifPresent(e -> {
            if (e.getHandledAt() == null) {
                e.setHandledAt(OffsetDateTime.now());
            }
        });
    }

    @Transactional(readOnly = true)
    public WhatsAppStatus status(UUID practiceId) {
        Reported reported = lastSessionReport.get();
        return new WhatsAppStatus(provider.enabled(), provider.sessionState().orElse(null),
                reported == null ? null : reported.state(), reported == null ? null : reported.at(),
                events.unhandledCount(practiceId));
    }

    private static int rank(MessageStatus status) {
        return switch (status) {
            case SENT -> 1;
            case DELIVERED -> 2;
            case READ -> 3;
            default -> 0;
        };
    }
}
