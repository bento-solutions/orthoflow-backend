-- Full-consultation mode: the doctor and the patient talk, the whole
-- conversation is transcribed, and what matters in it (identity, allergies,
-- history, the treatment plan, the next appointment) is proposed for the
-- doctor to validate, correct or remove before anything reaches the record.
--
-- This is deliberately not the command-dictation pipeline (voice_sessions):
-- that one discards speech not addressed to the system and keeps no transcript.
-- A consultation keeps the raw transcript, because the doctor asked to be able
-- to review what was actually said later. It is stored in exactly two places:
-- here, while the consultation is open (so a crashed tab loses nothing), and as
-- a CONSULTATION_TRANSCRIPT clinical note once the doctor saves.
--
-- The consultation owns a voice_session for its examination phase, where the
-- doctor dictates chart findings with the existing wake-word commands; chart
-- findings are reviewed and committed through that session, unchanged.

CREATE TABLE consultations (
    id                     UUID PRIMARY KEY,
    version                BIGINT NOT NULL DEFAULT 0,
    patient_id             UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    actor_id               UUID NOT NULL REFERENCES users(id),
    -- SET NULL, not CASCADE: the consultation (and its transcript) is the
    -- patient's record, the session is just the vehicle for chart commands.
    voice_session_id       UUID REFERENCES voice_sessions(id) ON DELETE SET NULL,
    status                 VARCHAR(16) NOT NULL
                           CHECK (status IN ('INTAKE', 'EXAMINATION', 'REVIEW', 'COMPLETED', 'ABANDONED')),
    locale                 VARCHAR(12),
    -- The doctor attested that the patient was told the whole conversation is
    -- transcribed by an outside service and kept in their file. Required to
    -- start; the code cannot verify it, only make it impossible to skip.
    patient_informed_at    TIMESTAMPTZ NOT NULL,
    -- Everything said, as transcribed. Replaced (not appended) on each save,
    -- because the client holds the complete text.
    transcript             TEXT,
    -- The latest extraction (JSON) — a proposal, never the record.
    draft                  TEXT,
    -- What the doctor validated at save (JSON), kept so the printed documents
    -- can be reproduced exactly as signed off.
    reviewed               TEXT,
    report                 TEXT,
    appointment_id         UUID,
    started_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    examination_started_at TIMESTAMPTZ,
    ended_at               TIMESTAMPTZ,
    -- Stamped when a save starts. Writing it is what takes the row (the version
    -- moves), so two saves of the same consultation cannot both run.
    save_started_at        TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ
);

CREATE INDEX idx_consultations_patient ON consultations(patient_id, started_at DESC);
-- "Is there an unfinished consultation for this patient" runs every time a
-- dossier opens.
CREATE INDEX idx_consultations_open ON consultations(patient_id)
    WHERE status IN ('INTAKE', 'EXAMINATION', 'REVIEW');

-- The raw transcript, filed on the patient's record when the doctor saves.
ALTER TABLE clinical_notes DROP CONSTRAINT clinical_notes_category_check;

ALTER TABLE clinical_notes ADD CONSTRAINT clinical_notes_category_check
    CHECK (category IN ('GENERAL', 'CHIEF_COMPLAINT', 'OBSERVATION', 'DENTAL_HISTORY',
                        'MEDICAL_HISTORY', 'DIAGNOSIS', 'FOLLOW_UP', 'TREATMENT_PLAN',
                        'CONSULTATION_REPORT', 'CONSULTATION_TRANSCRIPT'));
