-- The narrative a dentist reviews and signs at the end of a dictated
-- examination is now saved to the clinical record as a note, and gets a
-- category of its own so the dossier can tell it from a note dictated in the
-- middle of the examination.
--
-- Until now the text was kept only on voice_sessions.summary, which no screen
-- reads, while the review page told the dentist it was "saved as a clinical
-- note".

ALTER TABLE clinical_notes DROP CONSTRAINT clinical_notes_category_check;

ALTER TABLE clinical_notes ADD CONSTRAINT clinical_notes_category_check
    CHECK (category IN ('GENERAL', 'CHIEF_COMPLAINT', 'OBSERVATION', 'DENTAL_HISTORY',
                        'MEDICAL_HISTORY', 'DIAGNOSIS', 'FOLLOW_UP', 'TREATMENT_PLAN',
                        'CONSULTATION_REPORT'));
