package com.orthoflow.messaging.application.dto;

import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * What a producer hands to the outbox. Give either {@code body} (already
 * written, as in-app notifications are) or a {@code purpose} with
 * {@code variables} to be rendered from the clinic's template.
 */
@Getter
@Builder
public class OutgoingMessage {
    private final UUID practiceId;
    private final MessageChannel channel;
    private final MessagePurpose purpose;
    private final UUID patientId;
    /** Email address or phone as typed; for a patient-directed message, defaults to the patient's own. */
    private final String recipient;
    private final UUID recipientUserId;
    private final String language;
    private final Map<String, String> variables;
    private final String subject;
    private final String body;
    private final String relatedType;
    private final UUID relatedId;
    /** A second enqueue with the same key is a no-op: how a daily reminder run stays idempotent. */
    private final String dedupeKey;
    private final OffsetDateTime scheduledFor;
    private final UUID createdBy;
    /** For messages to staff or system mail (password reset) that no patient consent governs. */
    private final boolean skipConsentCheck;
}
