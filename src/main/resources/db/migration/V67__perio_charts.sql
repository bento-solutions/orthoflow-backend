-- Periodontal chart: the six-site probing record of one examination.
--
-- V62 keeps a coarse verdict per area of the mouth (healthy / gingivitis / periodontitis).
-- That verdict is what the chart is *for*; the chart itself is the evidence behind it:
-- probing depth, recession, bleeding, plaque, suppuration, mobility and furcation of every
-- tooth, charted again at each re-evaluation so the clinician can see whether the gums are
-- getting better or worse.
--
-- One row per examination. The measurements are a document, not rows of their own: a chart
-- is always read and written whole (autosave sends all of it), it is validated as a whole in
-- the service, and keeping it in one row makes the save atomic and the optimistic lock
-- meaningful. Only sites that carry a value are stored.
--
--   tooth_status  {"16": "missing", "26": "implant"}                  (absent = present)
--   teeth         {"18": {"mobility": 1,
--                         "pocketDepth": {"mesiobuccal": 4, ...},
--                         "recession":   {...},
--                         "bleeding":    ["buccal", ...],
--                         "plaque":      [...], "suppuration": [...],
--                         "furcation":   {"buccal": "II"}}, ...}
--
-- Deleting a chart keeps the row (deleted_at): a clinician who removes the wrong visit can
-- bring it back, and nothing here is ever re-computed from other tables.
CREATE TABLE perio_charts (
    id             UUID         PRIMARY KEY,
    practice_id    UUID         NOT NULL REFERENCES practices(id),
    patient_id     UUID         NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    chart_date     DATE         NOT NULL,
    visit_reason   VARCHAR(200),
    clinical_notes TEXT,
    tooth_status   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    teeth          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_by     UUID         NOT NULL REFERENCES users(id),
    updated_by     UUID         NOT NULL REFERENCES users(id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,
    CHECK (jsonb_typeof(tooth_status) = 'object'),
    CHECK (jsonb_typeof(teeth) = 'object')
);

CREATE INDEX idx_perio_charts_patient
    ON perio_charts (patient_id, chart_date DESC, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_perio_charts_patient_deleted
    ON perio_charts (patient_id, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;
