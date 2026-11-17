-- How far each user got through the first-login guide, one row per chapter. No row means the
-- chapter has not been seen, so everyone already signed up gets the guide on their next login.
-- A table of its own rather than a key in user_preferences: that blob is replaced whole by the
-- client, so a stale tab could erase "completed" and replay the guide.
CREATE TABLE user_guide_progress (
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    guide_key    VARCHAR(40) NOT NULL,
    status       VARCHAR(12) NOT NULL CHECK (status IN ('SNOOZED', 'COMPLETED', 'DISMISSED')),
    version      SMALLINT    NOT NULL,
    snooze_count SMALLINT    NOT NULL DEFAULT 0,
    last_step    VARCHAR(40),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, guide_key)
);
