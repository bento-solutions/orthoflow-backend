-- Dental chart: several findings of one kind on a tooth (caries on the mesial AND on the
-- distal), where and by whom a treatment was done, and the state of the gums.

-- 1. The same finding on two different surfaces of one tooth is two lesions, not a
--    duplicate. The old index (chart, tooth, code) folded them into one row and the
--    second surface overwrote the first. A whole-tooth finding (no surface) still
--    allows only one per code.
DROP INDEX IF EXISTS uq_tooth_findings_active;
CREATE UNIQUE INDEX uq_tooth_findings_active
    ON tooth_findings (chart_id, fdi, finding_code, COALESCE(surface, ''))
    WHERE status = 'ACTIVE';

-- 2. History. A patient arriving from another dentist brings work done years ago, by
--    someone else; the chart must say so instead of attributing it to today's visit.
--    performed_on is NULL when nobody knows the date ("a filling, long ago").
ALTER TABLE tooth_findings
    ADD COLUMN performed_on  DATE,
    ADD COLUMN origin        VARCHAR(16) NOT NULL DEFAULT 'THIS_CLINIC'
        CHECK (origin IN ('THIS_CLINIC', 'EXTERNAL')),
    ADD COLUMN provider_name VARCHAR(160);

-- 3. Gum state. Append-only: the current state of a region is its latest row and the
--    earlier rows are the periodontal history (healed gingivitis is clinically relevant).
--    stage is the 2018 periodontitis stage (I-IV) and only makes sense for PERIODONTITIS.
CREATE TABLE periodontal_assessments (
    id           UUID        PRIMARY KEY,
    practice_id  UUID        NOT NULL REFERENCES practices(id),
    patient_id   UUID        NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    region       VARCHAR(16) NOT NULL
        CHECK (region IN ('WHOLE_MOUTH', 'UPPER_RIGHT', 'UPPER_FRONT', 'UPPER_LEFT',
                          'LOWER_LEFT', 'LOWER_FRONT', 'LOWER_RIGHT')),
    condition    VARCHAR(16) NOT NULL CHECK (condition IN ('HEALTHY', 'GINGIVITIS', 'PERIODONTITIS')),
    stage        INTEGER     CHECK (stage BETWEEN 1 AND 4),
    note         TEXT,
    assessed_on  DATE        NOT NULL,
    recorded_by  UUID        NOT NULL REFERENCES users(id),
    source       VARCHAR(20) NOT NULL DEFAULT 'manual',
    session_id   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (stage IS NULL OR condition = 'PERIODONTITIS')
);

CREATE INDEX idx_periodontal_patient ON periodontal_assessments (patient_id, region, assessed_on DESC, created_at DESC);
