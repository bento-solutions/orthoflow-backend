-- Integration between public patient booking, personal registration links,
-- inbound urgent alerts, and appointment reminder reply tracking.

ALTER TABLE patients ADD COLUMN IF NOT EXISTS registration_completed_at TIMESTAMPTZ;

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS reply_status VARCHAR(24);
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS reply_received_at TIMESTAMPTZ;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS reply_note TEXT;

ALTER TABLE message_events ADD COLUMN IF NOT EXISTS is_urgent BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_message_events_urgent
    ON message_events (practice_id, is_urgent, handled_at, occurred_at DESC)
    WHERE is_urgent = TRUE;

CREATE TABLE IF NOT EXISTS registration_link_retries (
    id              UUID PRIMARY KEY,
    practice_id     UUID        NOT NULL REFERENCES practices(id) ON DELETE CASCADE,
    patient_id      UUID        NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    appointment_id  UUID        NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    recipient_phone VARCHAR(40) NOT NULL,
    language        VARCHAR(2)  NOT NULL DEFAULT 'fr',
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_reg_link_retries_due
    ON registration_link_retries (next_attempt_at)
    WHERE completed_at IS NULL;
