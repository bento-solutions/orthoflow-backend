-- ADR 0007, "enforced": every table an entity maps carries the clinic it belongs to,
-- so Hibernate's tenant filter (@TenantId) can scope every query and every load, and
-- a code path that forgets the clinic reads nothing instead of everything.
--
-- 1. The child tables that only reached their clinic through a parent get their own
--    practice_id, copied from that parent.
-- 2. Numbers that were unique across the whole database (invoice numbers, SKUs,
--    treatment codes, a patient's e-mail...) become unique within a clinic: two
--    clinics may both have an invoice INV-2026-...-00001.
-- 3. Document numbers come from a counter per clinic and series instead of one
--    database-wide sequence, so each clinic's numbering is its own and has no gaps
--    left by another clinic. The clinic that already exists continues from where
--    each sequence stood, so no number is ever issued twice.
-- 4. No practice_id has a default any more. A row whose clinic nobody chose used to
--    land silently in clinic 1; now the insert fails.

-- ── 1. A clinic on every mapped child table ─────────────────────────────────
CREATE FUNCTION pg_temp.add_practice(child TEXT, parent TEXT, fk TEXT) RETURNS void AS $$
BEGIN
    EXECUTE format('ALTER TABLE %I ADD COLUMN practice_id UUID REFERENCES practices(id)', child);
    EXECUTE format('UPDATE %I c SET practice_id = p.practice_id FROM %I p WHERE p.id = c.%I', child, parent, fk);
    EXECUTE format('ALTER TABLE %I ALTER COLUMN practice_id SET NOT NULL', child);
    EXECUTE format('CREATE INDEX idx_%s_practice ON %I (practice_id)', child, child);
END;
$$ LANGUAGE plpgsql;

-- Parents before children: tooth_states and tooth_findings copy from dental_charts.
SELECT pg_temp.add_practice('invoice_lines', 'invoices', 'invoice_id');
SELECT pg_temp.add_practice('payments', 'invoices', 'invoice_id');
SELECT pg_temp.add_practice('payment_plan_instalments', 'payment_plans', 'plan_id');
SELECT pg_temp.add_practice('billing_audit_log', 'users', 'actor_id');
SELECT pg_temp.add_practice('clinical_notes', 'patients', 'patient_id');
SELECT pg_temp.add_practice('dental_charts', 'patients', 'patient_id');
SELECT pg_temp.add_practice('tooth_states', 'dental_charts', 'chart_id');
SELECT pg_temp.add_practice('tooth_findings', 'dental_charts', 'chart_id');
SELECT pg_temp.add_practice('tooth_state_events', 'patients', 'patient_id');
SELECT pg_temp.add_practice('patient_allergies', 'patients', 'patient_id');
SELECT pg_temp.add_practice('patient_medical_history', 'patients', 'patient_id');
SELECT pg_temp.add_practice('patient_phones', 'patients', 'patient_id');
SELECT pg_temp.add_practice('voice_command_audit', 'users', 'actor_id');
SELECT pg_temp.add_practice('count_session_lines', 'count_sessions', 'count_session_id');
SELECT pg_temp.add_practice('purchase_order_lines', 'purchase_orders', 'purchase_order_id');
SELECT pg_temp.add_practice('delivery_note_lines', 'delivery_notes', 'delivery_note_id');
SELECT pg_temp.add_practice('vendor_invoice_lines', 'vendor_invoices', 'vendor_invoice_id');
SELECT pg_temp.add_practice('sales_order_lines', 'sales_orders', 'sales_order_id');
SELECT pg_temp.add_practice('treatment_consumables', 'treatments', 'treatment_id');
SELECT pg_temp.add_practice('patient_treatment_consumables', 'patient_treatments', 'patient_treatment_id');
SELECT pg_temp.add_practice('invoice_discounts', 'treatment_invoices', 'treatment_invoice_id');
SELECT pg_temp.add_practice('treatment_invoice_consumables', 'treatment_invoices', 'treatment_invoice_id');

-- ── 2. Unique within a clinic, not across the database ──────────────────────
ALTER TABLE invoices DROP CONSTRAINT invoices_invoice_number_key;
ALTER TABLE invoices ADD CONSTRAINT uq_invoices_number UNIQUE (practice_id, invoice_number);

ALTER TABLE patients DROP CONSTRAINT patients_email_key;
ALTER TABLE patients ADD CONSTRAINT uq_patients_email UNIQUE (practice_id, email);

ALTER TABLE stock_items DROP CONSTRAINT stock_items_sku_key;
ALTER TABLE stock_items ADD CONSTRAINT uq_stock_items_sku UNIQUE (practice_id, sku);

ALTER TABLE treatments DROP CONSTRAINT treatments_code_key;
ALTER TABLE treatments ADD CONSTRAINT uq_treatments_code UNIQUE (practice_id, code);

ALTER TABLE purchase_orders DROP CONSTRAINT purchase_orders_po_number_key;
ALTER TABLE purchase_orders DROP CONSTRAINT uq_purchase_orders_po_number;
ALTER TABLE purchase_orders ADD CONSTRAINT uq_purchase_orders_po_number UNIQUE (practice_id, po_number);

ALTER TABLE delivery_notes DROP CONSTRAINT delivery_notes_dn_number_key;
ALTER TABLE delivery_notes DROP CONSTRAINT uq_delivery_notes_dn_number;
ALTER TABLE delivery_notes ADD CONSTRAINT uq_delivery_notes_dn_number UNIQUE (practice_id, dn_number);

ALTER TABLE sales_orders DROP CONSTRAINT sales_orders_so_number_key;
ALTER TABLE sales_orders ADD CONSTRAINT uq_sales_orders_so_number UNIQUE (practice_id, so_number);

ALTER TABLE treatment_invoices DROP CONSTRAINT treatment_invoices_invoice_number_key;
ALTER TABLE treatment_invoices DROP CONSTRAINT uq_treatment_invoices_invoice_number;
ALTER TABLE treatment_invoices ADD CONSTRAINT uq_treatment_invoices_invoice_number UNIQUE (practice_id, invoice_number);

ALTER TABLE vendor_invoices DROP CONSTRAINT vendor_invoices_vendor_invoice_number_key;
ALTER TABLE vendor_invoices ADD CONSTRAINT uq_vendor_invoices_number UNIQUE (practice_id, vendor_invoice_number);

ALTER TABLE count_sessions DROP CONSTRAINT count_sessions_session_number_key;
ALTER TABLE count_sessions ADD CONSTRAINT uq_count_sessions_number UNIQUE (practice_id, session_number);

ALTER TABLE retrocession_statements DROP CONSTRAINT retrocession_statements_statement_number_key;
ALTER TABLE retrocession_statements ADD CONSTRAINT uq_retrocession_statements_number UNIQUE (practice_id, statement_number);

-- ── 3. Document numbers per clinic ──────────────────────────────────────────
CREATE TABLE document_counters (
    practice_id UUID        NOT NULL REFERENCES practices(id),
    series      VARCHAR(40) NOT NULL,
    last_value  BIGINT      NOT NULL CHECK (last_value >= 0),
    PRIMARY KEY (practice_id, series)
);

-- The clinic that exists today continues each series from its sequence.
DO $$
DECLARE
    pair TEXT[];
    v BIGINT;
    called BOOLEAN;
BEGIN
    FOREACH pair SLICE 1 IN ARRAY ARRAY[
        ['invoice', 'invoices_seq'],
        ['purchase_order', 'po_seq'],
        ['delivery_note', 'dn_seq'],
        ['treatment_invoice', 'treatment_invoice_seq'],
        ['vendor_invoice', 'vendor_invoice_seq'],
        ['count_session', 'count_session_seq'],
        ['patient_code', 'patient_code_seq'],
        ['tax_document', 'tax_document_seq'],
        ['retrocession_statement', 'retrocession_statement_seq']
    ] LOOP
        EXECUTE format('SELECT last_value, is_called FROM %I', pair[2]) INTO v, called;
        INSERT INTO document_counters (practice_id, series, last_value)
        VALUES ('00000000-0000-0000-0000-000000000001', pair[1], CASE WHEN called THEN v ELSE v - 1 END);
    END LOOP;
END $$;

-- ── 4. No silent default clinic ─────────────────────────────────────────────
DO $$
DECLARE
    t TEXT;
BEGIN
    FOR t IN
        SELECT table_name FROM information_schema.columns
        WHERE table_schema = current_schema() AND column_name = 'practice_id' AND column_default IS NOT NULL
    LOOP
        EXECUTE format('ALTER TABLE %I ALTER COLUMN practice_id DROP DEFAULT', t);
    END LOOP;
END $$;
