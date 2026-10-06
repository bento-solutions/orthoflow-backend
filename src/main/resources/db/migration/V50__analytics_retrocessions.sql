-- Denteam parity F5: what the clinic owes collaborating doctors, and what it aims to earn.
--
-- Open decision D4 (how the target clinics actually pay their collaborators) is
-- answered with flexibility rather than a guess: a rule can pay a percentage of
-- what was COLLECTED or of what was PRODUCED, can override the percentage per
-- treatment category, can net lab fees off first and can add a fixed monthly
-- amount. A practitioner has at most one rule on any given day, so a change of
-- terms is a new row, not an edit of history.

CREATE TABLE retrocession_rules (
    id                   UUID PRIMARY KEY,
    practice_id          UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                       REFERENCES practices(id),
    practitioner_id      UUID          NOT NULL REFERENCES practitioners(id),
    basis                VARCHAR(10)   NOT NULL CHECK (basis IN ('COLLECTED', 'PRODUCED')),
    rate_percent         NUMERIC(5,2)  NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    deduct_lab_fees      BOOLEAN       NOT NULL DEFAULT FALSE,
    fixed_monthly_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (fixed_monthly_amount >= 0),
    effective_from       DATE          NOT NULL,
    effective_to         DATE,
    notes                TEXT,
    created_by           UUID,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT retrocession_rule_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT retrocession_rules_no_overlap EXCLUDE USING gist (
        practitioner_id WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&)
);
CREATE INDEX idx_retrocession_rules_practice ON retrocession_rules (practice_id, practitioner_id);

CREATE TABLE retrocession_rule_overrides (
    rule_id      UUID         NOT NULL REFERENCES retrocession_rules(id) ON DELETE CASCADE,
    category     VARCHAR(50)  NOT NULL,
    rate_percent NUMERIC(5,2) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    PRIMARY KEY (rule_id, category)
);

-- Money handed over before a statement exists. How much of an advance is still
-- outstanding is derived from retrocession_advance_settlements, never stored.
CREATE TABLE retrocession_advances (
    id              UUID PRIMARY KEY,
    practice_id     UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                  REFERENCES practices(id),
    practitioner_id UUID          NOT NULL REFERENCES practitioners(id),
    advance_date    DATE          NOT NULL,
    amount          NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    method          VARCHAR(32),
    notes           TEXT,
    created_by      UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version         BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_retrocession_advances_practitioner ON retrocession_advances (practitioner_id, advance_date);

CREATE SEQUENCE retrocession_statement_seq;

-- A validated statement is a record, not a draft: the figures are computed on
-- the server at validation time and frozen. The only change allowed afterwards
-- is voiding it (see the trigger below), which frees the period for a new one.
CREATE TABLE retrocession_statements (
    id                UUID PRIMARY KEY,
    practice_id       UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                    REFERENCES practices(id),
    practitioner_id   UUID          NOT NULL REFERENCES practitioners(id),
    statement_number  VARCHAR(32)   NOT NULL UNIQUE,
    period_from       DATE          NOT NULL,
    period_to         DATE          NOT NULL,
    base_amount       NUMERIC(12,2) NOT NULL,
    lab_deduction     NUMERIC(12,2) NOT NULL,
    variable_amount   NUMERIC(12,2) NOT NULL,
    fixed_amount      NUMERIC(12,2) NOT NULL,
    adjustment_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    gross_amount      NUMERIC(12,2) NOT NULL CHECK (gross_amount >= 0),
    advances_deducted NUMERIC(12,2) NOT NULL CHECK (advances_deducted >= 0),
    net_amount        NUMERIC(12,2) NOT NULL CHECK (net_amount >= 0),
    filters           JSONB         NOT NULL DEFAULT '{}'::jsonb,
    rules_snapshot    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    notes             TEXT,
    validated_by      UUID,
    validated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    voided_at         TIMESTAMPTZ,
    voided_by         UUID,
    void_reason       TEXT,
    CONSTRAINT retrocession_statement_period CHECK (period_to >= period_from),
    CONSTRAINT retrocession_statements_no_overlap EXCLUDE USING gist (
        practitioner_id WITH =,
        daterange(period_from, period_to, '[]') WITH &&) WHERE (voided_at IS NULL)
);
CREATE INDEX idx_retrocession_statements_practice ON retrocession_statements (practice_id, period_from);

-- Detail lines. invoice_id is a real foreign key on purpose: it is how the
-- application knows an invoice has already paid someone and must not move to a
-- colleague. Only a patient *code* is kept here, never a name, so the snapshot
-- does not outlive a Law 09-08 erasure with identifying data in it.
CREATE TABLE retrocession_statement_lines (
    id             UUID PRIMARY KEY,
    statement_id   UUID          NOT NULL REFERENCES retrocession_statements(id),
    kind           VARCHAR(10)   NOT NULL CHECK (kind IN ('ITEM', 'LAB', 'FIXED', 'ADJUSTMENT')),
    item_date      DATE,
    invoice_id     UUID          REFERENCES invoices(id),
    invoice_number VARCHAR(32),
    patient_code   VARCHAR(30),
    category       VARCHAR(50),
    label          VARCHAR(255),
    base_amount    NUMERIC(12,2) NOT NULL,
    rate_percent   NUMERIC(5,2),
    amount         NUMERIC(12,2) NOT NULL,
    sort_order     INT           NOT NULL
);
CREATE INDEX idx_retrocession_lines_statement ON retrocession_statement_lines (statement_id, sort_order);
CREATE INDEX idx_retrocession_lines_invoice ON retrocession_statement_lines (invoice_id) WHERE invoice_id IS NOT NULL;

CREATE TABLE retrocession_advance_settlements (
    statement_id UUID          NOT NULL REFERENCES retrocession_statements(id),
    advance_id   UUID          NOT NULL REFERENCES retrocession_advances(id),
    amount       NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (statement_id, advance_id)
);
CREATE INDEX idx_retrocession_settlements_advance ON retrocession_advance_settlements (advance_id);

CREATE TABLE retrocession_payouts (
    id           UUID PRIMARY KEY,
    statement_id UUID          NOT NULL REFERENCES retrocession_statements(id),
    amount       NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    paid_date    DATE          NOT NULL,
    method       VARCHAR(32),
    reference    VARCHAR(128),
    notes        TEXT,
    recorded_by  UUID,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_retrocession_payouts_statement ON retrocession_payouts (statement_id);

-- ── Immutability ────────────────────────────────────────────────────────
CREATE FUNCTION retrocession_statement_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A validated retrocession statement cannot be deleted; void it instead';
    END IF;
    -- The one permitted change: voiding a statement that is not yet void.
    IF OLD.voided_at IS NULL AND NEW.voided_at IS NOT NULL
       AND to_jsonb(OLD) - 'voided_at' - 'voided_by' - 'void_reason'
         = to_jsonb(NEW) - 'voided_at' - 'voided_by' - 'void_reason' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'A validated retrocession statement is immutable';
END $$;

CREATE TRIGGER trg_retrocession_statement_guard
    BEFORE UPDATE OR DELETE ON retrocession_statements
    FOR EACH ROW EXECUTE FUNCTION retrocession_statement_guard();

CREATE FUNCTION retrocession_child_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Retrocession statement detail is immutable';
END $$;

CREATE TRIGGER trg_retrocession_lines_guard
    BEFORE UPDATE OR DELETE ON retrocession_statement_lines
    FOR EACH ROW EXECUTE FUNCTION retrocession_child_guard();
CREATE TRIGGER trg_retrocession_settlements_guard
    BEFORE UPDATE OR DELETE ON retrocession_advance_settlements
    FOR EACH ROW EXECUTE FUNCTION retrocession_child_guard();

-- ── Goals ───────────────────────────────────────────────────────────────
-- One revenue target per clinic per year, derived from the wizard's inputs.
-- The inputs are kept so the screen can reopen the wizard where it was left.
CREATE TABLE practice_goals (
    id                      UUID PRIMARY KEY,
    practice_id             UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                          REFERENCES practices(id),
    goal_year               SMALLINT      NOT NULL CHECK (goal_year BETWEEN 2000 AND 2100),
    basis                   VARCHAR(10)   NOT NULL DEFAULT 'COLLECTED' CHECK (basis IN ('COLLECTED', 'PRODUCED')),
    fixed_costs             NUMERIC(12,2) NOT NULL CHECK (fixed_costs >= 0),
    personal_needs          NUMERIC(12,2) NOT NULL CHECK (personal_needs >= 0),
    variable_cost_percent   NUMERIC(5,2)  NOT NULL CHECK (variable_cost_percent >= 0 AND variable_cost_percent < 100),
    working_days_per_month  NUMERIC(4,1)  NOT NULL CHECK (working_days_per_month > 0 AND working_days_per_month <= 31),
    monthly_target          NUMERIC(12,2) NOT NULL CHECK (monthly_target >= 0),
    updated_by              UUID,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version                 BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_practice_goal_year UNIQUE (practice_id, goal_year)
);
