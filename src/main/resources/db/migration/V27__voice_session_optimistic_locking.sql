-- Adds optimistic locking to voice_sessions to prevent double-commit races.
-- Without this, two concurrent commits can both read status = PENDING_REVIEW,
-- both pass the guard, and both write findings — doubling every entry.
ALTER TABLE voice_sessions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
