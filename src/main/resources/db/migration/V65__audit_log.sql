-- The audit log: who signed in or failed to, who changed a role, who exported or
-- erased a patient, and who read or wrote a patient's record (audit H3).
--
-- One append-only table. A row is never updated; it is deleted only by the
-- retention job (AuditRetentionJob), which says so to the trigger below with a
-- transaction-local setting, so a stray DELETE or UPDATE from application code or
-- a hand-run statement is refused. The application connects as a superuser today
-- (audit M3), so this is a guard against mistakes and casual tampering, not
-- against someone who owns the database; a restricted role is the second half.
--
-- practice_id is NULL only for events with no clinic yet: a sign-in for an e-mail
-- that does not exist. It is a plain column, not a tenant-filtered entity: the log
-- is written and read with explicit SQL, and an administrator reads only their
-- own clinic's rows (AuditLogService).
--
-- category: AUTH (sign-in, password), ADMIN (users, roles, settings),
-- DATA_RIGHTS (export, erase, anonymise), PHI_READ and PHI_WRITE (a patient's
-- record viewed or changed). detail is a small JSON object (never a password or
-- a token, never the record's content).

CREATE TABLE audit_log (
    id           UUID PRIMARY KEY,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    practice_id  UUID REFERENCES practices(id),
    category     VARCHAR(16)  NOT NULL CHECK (category IN ('AUTH', 'ADMIN', 'DATA_RIGHTS', 'PHI_READ', 'PHI_WRITE')),
    action       VARCHAR(60)  NOT NULL,
    actor_id     UUID,
    actor_label  VARCHAR(255),
    patient_id   UUID,
    target_type  VARCHAR(60),
    target_id    UUID,
    method       VARCHAR(8),
    route        VARCHAR(255),
    status       SMALLINT,
    ip           VARCHAR(64),
    detail       TEXT
);

CREATE INDEX idx_audit_log_practice_time ON audit_log (practice_id, occurred_at DESC);
CREATE INDEX idx_audit_log_patient ON audit_log (practice_id, patient_id, occurred_at DESC) WHERE patient_id IS NOT NULL;
CREATE INDEX idx_audit_log_category ON audit_log (practice_id, category, occurred_at DESC);
CREATE INDEX idx_audit_log_actor ON audit_log (practice_id, actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;

CREATE FUNCTION audit_log_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('orthoflow.audit_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'The audit log is append-only';
END $$;

CREATE TRIGGER trg_audit_log_guard
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_guard();

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_guard();
