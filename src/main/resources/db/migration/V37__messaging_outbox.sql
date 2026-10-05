-- Denteam parity F6: one outbox for every outbound message (email, WhatsApp,
-- in-app). Producers insert a QUEUED row inside their own transaction; a
-- scheduled sender delivers with retry and backoff. Nothing sends inline, so a
-- provider outage never fails the business action that triggered the message.
--
-- Law 09-08: message bodies carry no diagnoses, and body_purged_at records the
-- retention job blanking old bodies while keeping the delivery metadata.

CREATE TABLE message_templates (
    id          UUID PRIMARY KEY,
    practice_id UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                            REFERENCES practices(id),
    channel     VARCHAR(12) NOT NULL CHECK (channel IN ('EMAIL', 'WHATSAPP', 'IN_APP')),
    purpose     VARCHAR(40) NOT NULL,
    language    VARCHAR(2)  NOT NULL CHECK (language IN ('fr', 'ar', 'en')),
    subject     VARCHAR(255),
    body        TEXT        NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_message_template UNIQUE (practice_id, channel, purpose, language)
);

CREATE TABLE message_outbox (
    id                  UUID PRIMARY KEY,
    practice_id         UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                    REFERENCES practices(id),
    channel             VARCHAR(12) NOT NULL CHECK (channel IN ('EMAIL', 'WHATSAPP', 'IN_APP')),
    purpose             VARCHAR(40) NOT NULL,
    status              VARCHAR(12) NOT NULL DEFAULT 'QUEUED'
                        CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'CANCELLED')),
    recipient           VARCHAR(255),                -- email address or E.164 phone; null for IN_APP
    recipient_user_id   UUID REFERENCES users(id),   -- IN_APP target
    patient_id          UUID REFERENCES patients(id) ON DELETE SET NULL,
    language            VARCHAR(2)  NOT NULL DEFAULT 'fr',
    subject             VARCHAR(255),
    body                TEXT,
    related_type        VARCHAR(40),
    related_id          UUID,
    dedupe_key          VARCHAR(160) UNIQUE,         -- stops a reminder being queued twice
    scheduled_for       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempts            INT         NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error          TEXT,
    provider_message_id VARCHAR(160),
    sent_at             TIMESTAMPTZ,
    read_at             TIMESTAMPTZ,
    body_purged_at      TIMESTAMPTZ,
    created_by          UUID REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_outbox_due ON message_outbox (next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX idx_outbox_patient ON message_outbox (patient_id, created_at DESC);
CREATE INDEX idx_outbox_inapp ON message_outbox (recipient_user_id, read_at) WHERE channel = 'IN_APP';
CREATE INDEX idx_outbox_provider_id ON message_outbox (provider_message_id) WHERE provider_message_id IS NOT NULL;

-- Provider webhooks: delivered, read, failed, and inbound patient replies.
CREATE TABLE message_events (
    id          UUID PRIMARY KEY,
    practice_id UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                            REFERENCES practices(id),
    outbox_id   UUID REFERENCES message_outbox(id) ON DELETE CASCADE,
    direction   VARCHAR(8)  NOT NULL DEFAULT 'OUT' CHECK (direction IN ('OUT', 'IN')),
    event_type  VARCHAR(20) NOT NULL,
    external_id VARCHAR(160),               -- the provider's own id (wamid): makes at-least-once webhooks idempotent
    from_phone  VARCHAR(40),
    body        TEXT,
    patient_id  UUID REFERENCES patients(id) ON DELETE SET NULL,
    handled_at  TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_message_events_outbox ON message_events (outbox_id);
CREATE UNIQUE INDEX uq_message_events_external ON message_events (direction, event_type, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX idx_message_events_inbox ON message_events (direction, handled_at, occurred_at DESC);

-- Per-channel opt-in, recorded with its source (Law 09-08).
CREATE TABLE patient_channel_consent (
    patient_id UUID        NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    channel    VARCHAR(12) NOT NULL CHECK (channel IN ('EMAIL', 'WHATSAPP')),
    opted_in   BOOLEAN     NOT NULL,
    source     VARCHAR(30) NOT NULL DEFAULT 'STAFF',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (patient_id, channel)
);
