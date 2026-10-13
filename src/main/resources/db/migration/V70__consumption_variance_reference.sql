-- The reference data the leakage report and the order forecast both stand on.
--
-- The bill of materials itself already exists: treatment_consumables says a bonding
-- visit takes 20 ligatures, a pair of gloves and 10 cm of power chain, and since
-- stock_items carries a real unit of measure those numbers finally say what they are.
-- What was missing is everything needed to read a difference from it honestly.
--
-- 1. A TOLERANCE. "8 ml of anaesthetic" is a target, not a law — a syringe is not
--    drawn to the microlitre. With no allowance every single visit reports a gap and
--    the report is noise that people learn to ignore. variance_allowance_pct is the
--    band inside which a difference is simply how the work went.
--
-- 2. A REASON for the excess outside that band. A dropped bur, a bracket that failed
--    and had to be redone, a child who moved — these really did consume the material.
--    Recording why is what lets the report separate explained consumption from the
--    unexplained remainder, so it accuses leakage rather than accusing a bad morning.
--
-- 3. A RE-TREATMENT LINK. Redoing a bond consumes the kit a second time, legitimately.
--    Counting that as a gap would make whoever takes the hard cases look worst, so it
--    is excluded from leakage and reported on its own as a quality figure instead.

-- ── 1. Tolerance on each bill-of-materials line ────────────────────────────
ALTER TABLE treatment_consumables
    ADD COLUMN variance_allowance_pct NUMERIC(5,2) NOT NULL DEFAULT 10.00;

ALTER TABLE treatment_consumables
    ADD CONSTRAINT chk_tc_variance_allowance CHECK (variance_allowance_pct >= 0 AND variance_allowance_pct <= 100);

COMMENT ON COLUMN treatment_consumables.variance_allowance_pct IS
    'Percentage band around quantity_used inside which a difference is normal and never flagged.';

-- A liquid or a length is measured by eye and deserves a wider band than a countable
-- thing: you either used two brackets or you did not.
UPDATE treatment_consumables tc
   SET variance_allowance_pct = 20.00
  FROM stock_items si
 WHERE si.id = tc.stock_item_id
   AND si.unit_of_measure IN ('MILLILITRE', 'LITRE', 'GRAM', 'MILLIGRAM', 'MILLIMETRE', 'CENTIMETRE', 'METRE');

UPDATE treatment_consumables tc
   SET variance_allowance_pct = 0.00
  FROM stock_items si
 WHERE si.id = tc.stock_item_id
   AND si.unit_of_measure IN ('PIECE', 'PAIR', 'BOX', 'PACK', 'CARTRIDGE');

-- ── 2. Re-treatment ────────────────────────────────────────────────────────
ALTER TABLE patient_treatments
    ADD COLUMN retreatment_of_id UUID REFERENCES patient_treatments(id) ON DELETE SET NULL;

CREATE INDEX idx_patient_treatments_retreatment ON patient_treatments (practice_id, retreatment_of_id)
    WHERE retreatment_of_id IS NOT NULL;

COMMENT ON COLUMN patient_treatments.retreatment_of_id IS
    'Set when this redoes an earlier treatment. Its consumption is legitimate, so it is '
    'excluded from the leakage figure and reported as a re-treatment rate instead.';

-- ── 3. Explained excess ────────────────────────────────────────────────────
-- One row per "we used more of this than the bill of materials says, and here is why".
-- Deliberately not a column on the movement: a single movement can be part expected and
-- part excess, and only the excess needs explaining.
CREATE TABLE stock_consumption_variances (
    id                   UUID        PRIMARY KEY,
    practice_id          UUID        NOT NULL REFERENCES practices(id),
    patient_treatment_id UUID        NOT NULL REFERENCES patient_treatments(id) ON DELETE CASCADE,
    stock_item_id        UUID        NOT NULL REFERENCES stock_items(id),
    -- Always positive: the amount consumed over and above the bill of materials.
    quantity             NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    reason               VARCHAR(20) NOT NULL CHECK (reason IN (
                             'WASTAGE',        -- spilled, over-mixed, expired mid-use
                             'BREAKAGE',       -- dropped or snapped before it was used
                             'RE_TREATMENT',   -- the step had to be redone
                             'PATIENT_FACTOR', -- the patient moved, gagged, bled
                             'OTHER')),
    note                 TEXT,
    recorded_by          UUID        REFERENCES users(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_consumption_variance_treatment ON stock_consumption_variances (practice_id, patient_treatment_id);
CREATE INDEX idx_consumption_variance_item ON stock_consumption_variances (practice_id, stock_item_id, created_at DESC);
