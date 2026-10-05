-- Denteam parity phase 3: internal messages.
--
-- Plain staff-to-staff threads — a question to the front desk, a note for the next
-- shift — kept apart from patient messaging (the outbox) because they are not
-- outbound, carry no consent and need no provider. A thread belongs to its
-- participants and nobody else can read it, administrators included.

CREATE TABLE staff_threads (
    id              UUID PRIMARY KEY,
    practice_id     UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                 REFERENCES practices(id),
    subject         VARCHAR(200) NOT NULL,
    created_by      UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_message_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE staff_thread_participants (
    thread_id    UUID        NOT NULL REFERENCES staff_threads(id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_read_at TIMESTAMPTZ,
    PRIMARY KEY (thread_id, user_id)
);
CREATE INDEX idx_thread_participants_user ON staff_thread_participants (user_id);

CREATE TABLE staff_messages (
    id         UUID PRIMARY KEY,
    thread_id  UUID        NOT NULL REFERENCES staff_threads(id) ON DELETE CASCADE,
    sender_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_staff_messages_thread ON staff_messages (thread_id, created_at);
