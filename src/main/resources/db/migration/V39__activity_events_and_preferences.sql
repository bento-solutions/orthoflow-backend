-- Denteam parity F8: a generic activity log (who did what to which record),
-- written from the services. It feeds the appointment activity panel now and
-- an audit trail later; billing_audit_log can fold into it in a later release.

CREATE TABLE activity_events (
    id          UUID PRIMARY KEY,
    practice_id UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                             REFERENCES practices(id),
    actor_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_name  VARCHAR(200),
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   UUID         NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    diff        JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_activity_entity ON activity_events (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_activity_practice_time ON activity_events (practice_id, created_at DESC);

-- Per-user UI preferences (agenda view, visible filters, doctor order, patient
-- list columns). Opaque JSON: the frontend owns its shape.
CREATE TABLE user_preferences (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    prefs      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
