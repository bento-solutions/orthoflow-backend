-- Two gaps in the stock model that both come down to "one what?".
--
-- 1. UNIT OF MEASURE. stock_items.unit was free text, so a recipe line saying a
--    treatment uses "1" of something meant nothing on its own — one gram, one
--    millilitre, one glove? unit_of_measure is the constrained version the system can
--    actually reason about (and validate decimals against). The old column stays as the
--    wording a clinic likes to read: "box of 100 pairs" is a better label than "PAIR".
--
-- 2. MULTI-USE MATERIALS. A polishing disc is one unit of stock but survives five
--    polishes. Consuming it a sixth time should take a unit off the shelf, not the
--    first time. uses_per_unit is how many applications one unit yields; 1 (the
--    default, and what every existing row gets) means the old behaviour exactly —
--    one use, one unit.
--
--    uses_on_current_unit is how far through the unit in service we are. It is
--    deliberately a single running counter per item rather than per physical disc:
--    nobody at a chairside is going to record which of four identical discs they
--    picked up. At uses_per_unit the counter rolls over, one unit is deducted, and it
--    returns to zero — and a restore runs the same arithmetic backwards.

ALTER TABLE stock_items
    ADD COLUMN unit_of_measure VARCHAR(20),
    -- One use per unit is the old, implicit behaviour: a thing is consumed when used.
    ADD COLUMN uses_per_unit        INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN uses_on_current_unit INTEGER NOT NULL DEFAULT 0;

-- Backfill from the text that is actually in there today. Anything unrecognised is a
-- countable thing, which is what "unit" already meant for most of the catalogue.
UPDATE stock_items SET unit_of_measure = CASE lower(trim(unit))
    WHEN 'pair' THEN 'PAIR'
    WHEN 'ml'   THEN 'MILLILITRE'
    WHEN 'l'    THEN 'LITRE'
    WHEN 'g'    THEN 'GRAM'
    WHEN 'mg'   THEN 'MILLIGRAM'
    WHEN 'mm'   THEN 'MILLIMETRE'
    WHEN 'cm'   THEN 'CENTIMETRE'
    WHEN 'm'    THEN 'METRE'
    WHEN 'box'  THEN 'BOX'
    WHEN 'pack' THEN 'PACK'
    ELSE 'PIECE'
END;

ALTER TABLE stock_items
    ALTER COLUMN unit_of_measure SET NOT NULL,
    ADD CONSTRAINT chk_stock_unit_of_measure CHECK (unit_of_measure IN (
        'PIECE', 'PAIR', 'BOX', 'PACK', 'CARTRIDGE',
        'MILLIGRAM', 'GRAM',
        'MILLILITRE', 'LITRE',
        'MILLIMETRE', 'CENTIMETRE', 'METRE',
        'USE')),
    ADD CONSTRAINT chk_stock_uses_per_unit CHECK (uses_per_unit >= 1),
    -- Never at or past the rollover: reaching it is what triggers the deduction, so a
    -- stored counter that equals uses_per_unit would mean a unit was never taken off.
    ADD CONSTRAINT chk_stock_uses_on_current CHECK (uses_on_current_unit >= 0
                                                    AND uses_on_current_unit < uses_per_unit);
