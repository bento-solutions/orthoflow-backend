package com.orthoflow.messaging.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One outbound message. A producer inserts it QUEUED inside its own
 * transaction; the sender delivers it later, with retry. {@code body} is blanked
 * by the retention job once old, while the delivery facts stay.
 */
@Entity
@Table(name = "message_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxMessage {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessagePurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MessageStatus status = MessageStatus.QUEUED;

    private String recipient;

    @Column(name = "recipient_user_id")
    private UUID recipientUserId;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(nullable = false, length = 2)
    @Builder.Default
    private String language = "fr";

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "related_type")
    private String relatedType;

    @Column(name = "related_id")
    private UUID relatedId;

    @Column(name = "dedupe_key", unique = true)
    private String dedupeKey;

    @Column(name = "scheduled_for", nullable = false)
    private OffsetDateTime scheduledFor;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;

    @Column(name = "body_purged_at")
    private OffsetDateTime bodyPurgedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (scheduledFor == null) scheduledFor = now;
        if (nextAttemptAt == null) nextAttemptAt = scheduledFor;
    }
}
