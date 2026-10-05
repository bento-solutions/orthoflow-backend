-- Denteam parity phase 1.3: what the patient list and the new-patient form need.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── Insurers (replaces the free-text provider; phase 2 tax documents need it) ──
CREATE TABLE insurers (
    id          UUID PRIMARY KEY,
    practice_id UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                             REFERENCES practices(id),
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    kind        VARCHAR(10)  NOT NULL DEFAULT 'PRIVATE' CHECK (kind IN ('PUBLIC', 'PRIVATE')),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_insurer_code UNIQUE (practice_id, code)
);
INSERT INTO insurers (id, code, name, kind) VALUES
 (gen_random_uuid(), 'CNOPS',  'CNOPS',                         'PUBLIC'),
 (gen_random_uuid(), 'CNSS',   'CNSS (AMO)',                    'PUBLIC'),
 (gen_random_uuid(), 'WAFA',   'Wafa Assurance',                'PRIVATE'),
 (gen_random_uuid(), 'SAHAM',  'Saham Assurance',               'PRIVATE'),
 (gen_random_uuid(), 'AXA',    'AXA Assurance Maroc',           'PRIVATE'),
 (gen_random_uuid(), 'RMA',    'RMA',                           'PRIVATE'),
 (gen_random_uuid(), 'ATLANTA','AtlantaSanad',                  'PRIVATE'),
 (gen_random_uuid(), 'MGPAP',  'MGPAP',                         'PRIVATE'),
 (gen_random_uuid(), 'FAR',    'Mutuelle des FAR',              'PUBLIC');

-- ── Referral sources (a configurable list) ─────────────────────────────
CREATE TABLE referral_sources (
    id            UUID PRIMARY KEY,
    practice_id   UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                               REFERENCES practices(id),
    name          VARCHAR(120) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_referral_source UNIQUE (practice_id, name)
);
INSERT INTO referral_sources (id, name, display_order) VALUES
 (gen_random_uuid(), 'Bouche à oreille', 1), (gen_random_uuid(), 'Recommandé par un confrère', 2),
 (gen_random_uuid(), 'Réseaux sociaux', 3), (gen_random_uuid(), 'Recherche internet', 4),
 (gen_random_uuid(), 'Passage devant le cabinet', 5), (gen_random_uuid(), 'Autre', 9);

-- ── Patient columns ────────────────────────────────────────────────────
CREATE SEQUENCE patient_code_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE patients
    ADD COLUMN patient_code        VARCHAR(30),
    ADD COLUMN photo_file_id       UUID REFERENCES files(id),
    ADD COLUMN occupation          VARCHAR(150),
    ADD COLUMN referral_source     VARCHAR(120),
    ADD COLUMN global_discount_pct NUMERIC(5,2) NOT NULL DEFAULT 0
                                   CHECK (global_discount_pct >= 0 AND global_discount_pct <= 100),
    ADD COLUMN preferred_language  VARCHAR(2) NOT NULL DEFAULT 'fr'
                                   CHECK (preferred_language IN ('fr', 'en', 'ar')),
    ADD COLUMN insurer_id          UUID REFERENCES insurers(id);

-- Existing patients get codes in the order they were registered.
UPDATE patients p
SET patient_code = 'P-' || lpad(n.rn::text, 5, '0')
FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn FROM patients) n
WHERE p.id = n.id;
SELECT setval('patient_code_seq', GREATEST((SELECT count(*) FROM patients), 1));

ALTER TABLE patients ALTER COLUMN patient_code SET NOT NULL;
ALTER TABLE patients ALTER COLUMN patient_code
    SET DEFAULT ('P-' || lpad(nextval('patient_code_seq')::text, 5, '0'));
ALTER TABLE patients ADD CONSTRAINT uq_patients_code UNIQUE (practice_id, patient_code);

-- Link the free-text insurer to the table where the name matches.
UPDATE patients p
SET insurer_id = i.id
FROM insurers i
WHERE p.insurer_id IS NULL AND p.insurance_provider IS NOT NULL
  AND i.practice_id = p.practice_id
  AND (lower(btrim(p.insurance_provider)) = lower(i.code) OR lower(btrim(p.insurance_provider)) = lower(i.name));

CREATE TABLE patient_phones (
    id         UUID PRIMARY KEY,
    patient_id UUID         NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    number     VARCHAR(40)  NOT NULL,
    label      VARCHAR(40),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_patient_phones_patient ON patient_phones (patient_id);

-- ── Duplicate detection ────────────────────────────────────────────────
CREATE FUNCTION phone_digits(raw TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN raw IS NULL THEN NULL
                ELSE nullif(right(regexp_replace(raw, '[^0-9]', '', 'g'), 9), '') END
$$;

CREATE INDEX idx_patients_name_trgm ON patients
    USING gin ((lower(first_name || ' ' || last_name)) gin_trgm_ops) WHERE deleted_at IS NULL;
CREATE INDEX idx_patients_phone_digits ON patients (phone_digits(phone)) WHERE deleted_at IS NULL;
CREATE INDEX idx_patients_cin_lower ON patients (lower(cin)) WHERE deleted_at IS NULL AND cin IS NOT NULL;
CREATE INDEX idx_patients_created ON patients (created_at);
CREATE INDEX idx_patients_status ON patients (status);
