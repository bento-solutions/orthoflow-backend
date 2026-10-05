-- Denteam parity phases 2.2 - 2.4: instalment plans for treatment contracts, the
-- cheque register, and the daily cash close.

-- ── Instalment plans (ortho contracts) ────────────────────────────────
CREATE TABLE payment_plans (
    id                   UUID PRIMARY KEY,
    practice_id          UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                       REFERENCES practices(id),
    patient_id           UUID          NOT NULL REFERENCES patients(id) ON DELETE RESTRICT,
    patient_treatment_id UUID REFERENCES patient_treatments(id) ON DELETE SET NULL,
    invoice_id           UUID REFERENCES invoices(id) ON DELETE SET NULL,
    practitioner_id      UUID REFERENCES practitioners(id),
    total                NUMERIC(12,2) NOT NULL CHECK (total > 0),
    down_payment         NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (down_payment >= 0),
    instalment_count     INT           NOT NULL CHECK (instalment_count BETWEEN 1 AND 120),
    frequency            VARCHAR(12)   NOT NULL CHECK (frequency IN ('WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY')),
    start_date           DATE          NOT NULL,
    status               VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    notes                TEXT,
    created_by           UUID,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_plan_down_payment CHECK (down_payment < total)
);
CREATE INDEX idx_payment_plans_patient ON payment_plans (patient_id);

CREATE TABLE payment_plan_instalments (
    id          UUID PRIMARY KEY,
    plan_id     UUID          NOT NULL REFERENCES payment_plans(id) ON DELETE CASCADE,
    seq         INT           NOT NULL,                  -- 0 is the down payment, 1..n the instalments
    due_date    DATE          NOT NULL,
    amount      NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    paid_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    status      VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
    paid_at     TIMESTAMPTZ,
    receipt_id  UUID REFERENCES receipts(id) ON DELETE SET NULL,
    CONSTRAINT uq_instalment_seq UNIQUE (plan_id, seq)
);
CREATE INDEX idx_instalments_due ON payment_plan_instalments (due_date) WHERE status = 'PENDING';

-- ── Cheque register ───────────────────────────────────────────────────
CREATE TABLE cheques (
    id            UUID PRIMARY KEY,
    practice_id   UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                REFERENCES practices(id),
    number        VARCHAR(40)   NOT NULL,
    bank          VARCHAR(100),
    drawer_name   VARCHAR(200),                         -- the patient, or another party
    patient_id    UUID REFERENCES patients(id) ON DELETE RESTRICT,
    amount        NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    due_date      DATE          NOT NULL,               -- post-dated cheques are common
    deposit_date  DATE,
    cashed_date   DATE,
    status        VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DEPOSITED', 'CASHED', 'REJECTED')),
    is_guarantee  BOOLEAN       NOT NULL DEFAULT FALSE,  -- a guarantee cheque is held, not a receipt
    receipt_id    UUID REFERENCES receipts(id) ON DELETE SET NULL,
    notes         TEXT,
    created_by    UUID,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_guarantee_has_no_receipt CHECK (NOT is_guarantee OR receipt_id IS NULL)
);
CREATE INDEX idx_cheques_status_due ON cheques (practice_id, status, due_date);
ALTER TABLE receipts ADD CONSTRAINT fk_receipts_cheque FOREIGN KEY (cheque_id) REFERENCES cheques(id);

-- ── Daily cash close ──────────────────────────────────────────────────
CREATE TABLE cash_closings (
    id           UUID PRIMARY KEY,
    practice_id  UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                             REFERENCES practices(id),
    closing_date DATE        NOT NULL,
    closed_by    UUID,
    notes        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cash_closing_day UNIQUE (practice_id, closing_date)
);

CREATE TABLE cash_closing_lines (
    id         UUID PRIMARY KEY,
    closing_id UUID          NOT NULL REFERENCES cash_closings(id) ON DELETE CASCADE,
    method     VARCHAR(32)   NOT NULL,
    expected   NUMERIC(12,2) NOT NULL,
    counted    NUMERIC(12,2) NOT NULL,
    CONSTRAINT uq_closing_line UNIQUE (closing_id, method)
);
