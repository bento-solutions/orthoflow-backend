package com.orthoflow.messaging.application.dto;

import com.orthoflow.messaging.domain.model.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public final class MessagingDtos {

    private MessagingDtos() {
    }

    public record LogRow(UUID id, MessageChannel channel, MessagePurpose purpose, MessageStatus status,
                         String recipient, UUID patientId, String subject, String body, int attempts,
                         String lastError, OffsetDateTime scheduledFor, OffsetDateTime sentAt,
                         OffsetDateTime createdAt, boolean bodyPurged) {
        public static LogRow from(OutboxMessage m) {
            return new LogRow(m.getId(), m.getChannel(), m.getPurpose(), m.getStatus(), m.getRecipient(),
                    m.getPatientId(), m.getSubject(), m.getBody(), m.getAttempts(), m.getLastError(),
                    m.getScheduledFor(), m.getSentAt(), m.getCreatedAt(), m.getBodyPurgedAt() != null);
        }
    }

    public record TemplateRow(MessagePurpose purpose, MessageChannel channel, String language, String subject,
                              String body, boolean active, boolean customised) {
    }

    public record TemplateUpsert(@NotNull MessagePurpose purpose, @NotNull MessageChannel channel,
                                 @NotNull @Pattern(regexp = "fr|en|ar") String language, String subject,
                                 @NotBlank String body, Boolean active) {
    }

    public record PreviewRequest(@NotNull MessagePurpose purpose, @NotNull MessageChannel channel,
                                 @NotNull @Pattern(regexp = "fr|en|ar") String language, String subject,
                                 @NotBlank String body) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "TemplatePreview")

    public record Preview(String subject, String body) {
    }

    public record SendTest(@NotNull MessageChannel channel, @NotBlank String recipient) {
    }

    public record InboxRow(UUID id, String fromPhone, String body, UUID patientId, String patientName,
                           OffsetDateTime occurredAt, OffsetDateTime handledAt) {
    }

    public record Consent(Map<MessageChannel, Boolean> channels) {
    }

    public record SetConsent(@NotNull MessageChannel channel, boolean optedIn) {
    }

    public record WhatsAppStatus(boolean enabled, String sessionState, String lastReportedState,
                                 OffsetDateTime lastReportedAt, long unhandledReplies) {
    }

    public record NotificationRow(UUID id, MessagePurpose purpose, String subject, String body, String relatedType,
                                  UUID relatedId, boolean read, OffsetDateTime createdAt) {
        public static NotificationRow from(OutboxMessage m) {
            return new NotificationRow(m.getId(), m.getPurpose(), m.getSubject(), m.getBody(), m.getRelatedType(),
                    m.getRelatedId(), m.getReadAt() != null, m.getCreatedAt());
        }
    }
}
