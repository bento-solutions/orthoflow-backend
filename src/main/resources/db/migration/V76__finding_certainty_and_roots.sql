-- The clinical referential ("Référentiel des états cliniques dentaires") asks for two things the
-- finding record could not hold.
--
-- 1. A state the dentist suspects but has not confirmed ("suspicion de carie", "fracture probable")
--    stays "suspected" until they confirm it. It used to be recorded as a plain finding, which is a
--    false statement in a clinical record. Existing rows are all confirmed: nobody could record a
--    suspicion before.
ALTER TABLE tooth_findings
    ADD COLUMN certainty VARCHAR(12) NOT NULL DEFAULT 'CONFIRMED'
        CHECK (certainty IN ('CONFIRMED', 'SUSPECTED'));

-- 2. A root of a back tooth can be named ("racine mésiale"): a lesion, a fracture or a resorption
--    is in one of them. Its names (mesialroot, distalroot, buccalroot, lingualroot) are longer than
--    the faces', and up to five parts are joined, so the column needs more than 48.
ALTER TABLE tooth_findings ALTER COLUMN surface TYPE VARCHAR(64);
