-- Denteam parity F1: practitioners as real records.
--
-- Until now an appointment had no doctor at all and
-- patient_treatments.doctor_name was free text. A practitioner may or may not
-- have a login (a visiting orthodontist usually does not), hence the nullable
-- user_id.

CREATE TABLE practitioners (
    id            UUID PRIMARY KEY,
    practice_id   UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                               REFERENCES practices(id),
    user_id       UUID UNIQUE  REFERENCES users(id),
    display_name  VARCHAR(150) NOT NULL,
    color         VARCHAR(9)   NOT NULL DEFAULT '#2563eb',
    specialty     VARCHAR(40)  NOT NULL DEFAULT 'ORTHODONTICS',
    inpe          VARCHAR(20),
    display_order INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_practitioners_practice ON practitioners (practice_id, active, display_order);

ALTER TABLE appointments       ADD COLUMN practitioner_id UUID REFERENCES practitioners(id);
ALTER TABLE patient_treatments ADD COLUMN practitioner_id UUID REFERENCES practitioners(id);
ALTER TABLE invoices           ADD COLUMN practitioner_id UUID REFERENCES practitioners(id);
ALTER TABLE patients           ADD COLUMN primary_practitioner_id UUID REFERENCES practitioners(id);

CREATE INDEX idx_appointments_practitioner ON appointments (practitioner_id, date_time);
CREATE INDEX idx_patient_treatments_practitioner ON patient_treatments (practitioner_id);
CREATE INDEX idx_patients_primary_practitioner ON patients (primary_practitioner_id);

-- ── Backfill ────────────────────────────────────────────────────────────
-- Every DOCTOR login becomes a practitioner linked to that login.
INSERT INTO practitioners (id, practice_id, user_id, display_name, display_order)
SELECT gen_random_uuid(), u.practice_id, u.id,
       btrim(u.first_name || ' ' || u.last_name),
       (row_number() OVER (ORDER BY u.created_at))::int
FROM users u
WHERE u.role = 'DOCTOR' AND u.active;

-- Free-text doctor names are matched to those practitioners on "First Last"
-- or "Last First", ignoring case and a leading "Dr"/"Dr."/"Docteur". Names that
-- match nothing stay NULL and are listed in practitioner_backfill_unmatched so
-- an admin can resolve them from Settings > Team rather than guess here.
CREATE FUNCTION normalise_doctor_name(raw TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $$
    SELECT lower(btrim(regexp_replace(btrim(raw), '^(dr\.?|docteur|pr\.?)\s+', '', 'i')))
$$;

UPDATE patient_treatments pt
SET practitioner_id = p.id
FROM practitioners p
JOIN users u ON u.id = p.user_id
WHERE pt.doctor_name IS NOT NULL
  AND normalise_doctor_name(pt.doctor_name) IN (
        lower(u.first_name || ' ' || u.last_name),
        lower(u.last_name || ' ' || u.first_name));

CREATE TABLE practitioner_backfill_unmatched (
    doctor_name TEXT PRIMARY KEY,
    row_count   INT NOT NULL
);

INSERT INTO practitioner_backfill_unmatched (doctor_name, row_count)
SELECT btrim(doctor_name), count(*)
FROM patient_treatments
WHERE doctor_name IS NOT NULL AND btrim(doctor_name) <> '' AND practitioner_id IS NULL
GROUP BY btrim(doctor_name);

-- Existing appointments are deliberately left without a practitioner: a
-- clinic that never tracked doctors may have overlapping chair-less visits, and
-- stamping one doctor on all of them would make the exclusion constraint below
-- fail the migration. The agenda shows unassigned visits in an "unassigned"
-- column until someone assigns them.

-- ── One doctor, one place at a time ─────────────────────────────────────
-- Mirrors appointments_no_chair_overlap (V21). Only non-cancelled bookings
-- with a practitioner take part, so unassigned legacy rows never conflict.
ALTER TABLE appointments ADD CONSTRAINT appointments_no_practitioner_overlap
    EXCLUDE USING gist (
        practitioner_id WITH =,
        appointment_slot_range(date_time, duration_minutes) WITH &&
    ) WHERE (practitioner_id IS NOT NULL AND status NOT IN ('CANCELLED', 'NO_SHOW'));
