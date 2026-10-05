-- Denteam parity phase 2.1: the patient account.
--
-- Until now a payment belonged to exactly one invoice (payments.invoice_id NOT
-- NULL), so an advance, a payment covering two invoices or a credit was
-- impossible. A RECEIPT is money received from a patient; the rows of `payments`
-- become its ALLOCATIONS — the part of a receipt applied to one invoice. Money not
-- yet allocated is the patient's credit.
--
-- payments is deliberately kept as the allocation table rather than replaced:
-- everything that reads an invoice's paid amount (status, balance, reports)
-- already reads payments, and keeps working unchanged. Each existing payment
-- becomes one receipt with one allocation, sharing its id.

CREATE TABLE receipts (
    id               UUID PRIMARY KEY,
    practice_id      UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                   REFERENCES practices(id),
    patient_id       UUID          NOT NULL REFERENCES patients(id) ON DELETE RESTRICT,
    amount           NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    method           VARCHAR(32)   NOT NULL,
    receipt_date     DATE          NOT NULL,
    practitioner_id  UUID REFERENCES practitioners(id),
    cheque_id        UUID,                         -- FK added with the cheque register (V43)
    reference        VARCHAR(128),
    notes            TEXT,
    recorded_by      UUID          NOT NULL,       -- no FK: payments.recorded_by never had one
    voided_at        TIMESTAMPTZ,
    voided_by        UUID,
    void_reason      TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_receipts_patient ON receipts (patient_id);
CREATE INDEX idx_receipts_practice_date ON receipts (practice_id, receipt_date) WHERE voided_at IS NULL;

ALTER TABLE payments ADD COLUMN receipt_id UUID REFERENCES receipts(id) ON DELETE RESTRICT;

INSERT INTO receipts (id, practice_id, patient_id, amount, method, receipt_date, practitioner_id, reference, notes, recorded_by, created_at)
SELECT p.id, i.practice_id, i.patient_id, p.amount, p.method, p.payment_date, i.practitioner_id, p.reference, p.notes,
       p.recorded_by, p.created_at
FROM payments p JOIN invoices i ON i.id = p.invoice_id;

UPDATE payments SET receipt_id = id;

CREATE INDEX idx_payments_receipt ON payments (receipt_id);

-- A payment can never exceed what its receipt brought in; enforced in the service
-- under a row lock, and checked here by a constraint trigger would be heavier than
-- the benefit, so the invariant is asserted by tests and the nightly reconciliation
-- query in the runbook instead.
