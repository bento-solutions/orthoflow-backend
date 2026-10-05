package com.orthoflow.messaging.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A provider callback (delivered, read, failed) or a patient's inbound reply. */
@Entity
@Table(name = "message_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageEvent {

    @Id
    private UUID id;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(name = "outbox_id")
    private UUID outboxId;

    @Column(nullable = false)
    @Builder.Default
    private String direction = "OUT";

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "from_phone")
    private String fromPhone;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "handled_at")
    private OffsetDateTime handledAt;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (occurredAt == null) occurredAt = OffsetDateTime.now();
    }
}
