-- The consultation log: what was said, line by line, with the time each line was
-- heard, so the doctor can come back after validating and check what the
-- proposals were read from.
--
-- Until now the conversation lived in consultations.transcript as one block of
-- text with no times. It stays (the model reads it, and it is what a crashed tab
-- restores), but the log is these rows: append-only, one per utterance, keyed by
-- the browser's own sequence number so a retried request cannot duplicate a line.
--
-- Kept or erased exactly like the block of text: ConsultationRecordWriter erases
-- them on save when orthoflow.consultation.retain-transcript is off, and
-- discarding a consultation erases them. They go with the consultation, and so
-- with the patient (ON DELETE CASCADE from consultations, itself cascaded from
-- patients).

CREATE TABLE consultation_transcript_lines (
    id              UUID PRIMARY KEY,
    practice_id     UUID NOT NULL REFERENCES practices(id),
    consultation_id UUID NOT NULL REFERENCES consultations(id) ON DELETE CASCADE,
    -- The browser's running number for the line within this consultation.
    seq             INTEGER NOT NULL CHECK (seq >= 0),
    -- When the line was heard (the browser's clock, held to the consultation's
    -- own span by the server). recorded_at is the server's clock, so a drifting
    -- laptop clock is visible rather than silently believed.
    spoken_at       TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    phase           VARCHAR(12) NOT NULL CHECK (phase IN ('INTAKE', 'EXAMINATION')),
    -- Typed by the doctor instead of heard.
    typed           BOOLEAN NOT NULL DEFAULT FALSE,
    text            TEXT NOT NULL,
    CONSTRAINT uq_consultation_line_seq UNIQUE (consultation_id, seq)
);

CREATE INDEX idx_consultation_lines_practice ON consultation_transcript_lines (practice_id);
