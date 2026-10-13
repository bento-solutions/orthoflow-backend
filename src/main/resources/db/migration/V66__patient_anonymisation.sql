-- Anonymisation: the way to honour an erasure request for a patient whose invoices,
-- receipts, cheques, payment plans or tax documents the law makes the clinic keep
-- (audit H2). The patient row stays so those records keep their reference, but it is
-- stripped of everything that identifies the person (PatientService#anonymisePatient);
-- everything clinical about them is deleted, and the financial records are kept with
-- their free text blanked.
--
-- anonymised_at is when it happened. It is also what stops the same patient being
-- anonymised twice and what lets a report say how many were.

ALTER TABLE patients ADD COLUMN anonymised_at TIMESTAMPTZ;
