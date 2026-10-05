-- Denteam parity, decision D1: a clinic identity from day one.
--
-- ADR 0002 deferred tenancy and called retrofitting it "the single most
-- expensive thing to change later". The product owner decided a shared,
-- multi-clinic system is coming, so every aggregate root gets a practice_id
-- now, while the tables are small. The column is added with a DEFAULT of the
-- one existing clinic, so rows already in production are backfilled
-- atomically and code that has not been taught about it yet keeps working;
-- query-level isolation is enforced module by module (see ADR 0007).
--
-- The default clinic reuses 00000000-0000-0000-0000-000000000001 because both
-- the frontend (invoice-create) and TreatmentInvoiceService already stamp that
-- value on every invoice they create.

CREATE TABLE practices (
    id         UUID PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version    BIGINT       NOT NULL DEFAULT 0
);

INSERT INTO practices (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Cabinet');

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'users', 'patients', 'appointments', 'chairs',
        'stock_items', 'stock_movements', 'suppliers',
        'treatments', 'patient_treatments', 'treatment_invoices',
        'sales_orders', 'purchase_orders', 'delivery_notes',
        'vendor_invoices', 'count_sessions',
        'consultations', 'voice_sessions'
    ] LOOP
        EXECUTE format(
            'ALTER TABLE %I ADD COLUMN practice_id UUID NOT NULL '
            'DEFAULT %L REFERENCES practices(id)',
            t, '00000000-0000-0000-0000-000000000001');
        EXECUTE format('CREATE INDEX idx_%s_practice ON %I (practice_id)', t, t);
    END LOOP;
END $$;

-- invoices.practice_id has existed since V1 but pointed nowhere. Deployments
-- are single-clinic until now, so any value other than the default is a
-- client-supplied placeholder: normalise it, then enforce the relationship.
UPDATE invoices SET practice_id = '00000000-0000-0000-0000-000000000001'
WHERE practice_id <> '00000000-0000-0000-0000-000000000001';

ALTER TABLE invoices
    ADD CONSTRAINT fk_invoices_practice FOREIGN KEY (practice_id) REFERENCES practices(id);

-- practice_settings was a singleton per deployment; it is now one row per
-- clinic. The singleton row keeps its id and is bound to the default clinic.
ALTER TABLE practice_settings
    ADD COLUMN practice_id UUID NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES practices(id);
ALTER TABLE practice_settings
    ADD CONSTRAINT uq_practice_settings_practice UNIQUE (practice_id);
