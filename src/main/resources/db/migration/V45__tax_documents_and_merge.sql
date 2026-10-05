-- Denteam parity phases 2.8 and 2.9: tax documents, insurance act codes, and the
-- trail a patient merge leaves behind.

-- Insurance act code and coefficient on the treatment catalogue. Left empty on
-- purpose: the Moroccan NGAP coding of orthodontic acts must be confirmed with a
-- clinic before values are entered, and a wrong code on a mutual-insurance form
-- is worse than a blank one.
ALTER TABLE treatments
    ADD COLUMN act_code        VARCHAR(30),
    ADD COLUMN act_coefficient NUMERIC(8,2);

CREATE SEQUENCE tax_document_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE tax_documents (
    id              UUID PRIMARY KEY,
    practice_id     UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                  REFERENCES practices(id),
    kind            VARCHAR(12)   NOT NULL CHECK (kind IN ('FEE_NOTE', 'CARE_FORM')),
    number          VARCHAR(40)   NOT NULL,
    patient_id      UUID          NOT NULL REFERENCES patients(id) ON DELETE RESTRICT,
    invoice_id      UUID REFERENCES invoices(id) ON DELETE SET NULL,
    practitioner_id UUID REFERENCES practitioners(id),
    insurer_id      UUID REFERENCES insurers(id),
    amount          NUMERIC(12,2) NOT NULL,
    issued_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    delivered_at    TIMESTAMPTZ,
    duplicate_of    UUID REFERENCES tax_documents(id),
    status          VARCHAR(10)   NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED', 'DELIVERED', 'VOID')),
    file_id         UUID REFERENCES files(id),
    notes           TEXT,
    created_by      UUID,
    CONSTRAINT uq_tax_document_number UNIQUE (practice_id, number)
);
CREATE INDEX idx_tax_documents_patient ON tax_documents (patient_id);
CREATE INDEX idx_tax_documents_issued ON tax_documents (practice_id, issued_at);

-- A merged patient is archived, not deleted, and points at the record that absorbed it.
ALTER TABLE patients ADD COLUMN merged_into_id UUID REFERENCES patients(id);
