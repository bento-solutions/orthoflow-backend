-- What the doctor has decided so far on a consultation's panel — validated,
-- corrected, removed or typed items, the report text, the chart findings left
-- out — so a reload does not undo their review. The browser's own state, kept
-- as JSON; it is not the record. Erased when the consultation is saved or
-- discarded, like the transcript and the draft.
ALTER TABLE consultations ADD COLUMN review_state TEXT;

-- When anything last happened on the consultation. An open consultation left
-- untouched for long enough is discarded, transcript and all, by a scheduled
-- job: a conversation nobody finished must not stay on the server forever.
ALTER TABLE consultations ADD COLUMN last_activity_at TIMESTAMPTZ;
UPDATE consultations
   SET last_activity_at = COALESCE(ended_at, examination_started_at, started_at);
