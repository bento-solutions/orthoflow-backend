-- How a consultation acts on what is said (docs/CONSULTATION.md §1):
--   LIVE      the chart commands act while the doctor speaks (what every consultation did before);
--   DEFERRED  nothing acts while recording; when the consultation ends, the whole conversation is
--             replayed through the commands and read by the model, then put to the doctor to validate.
-- Existing rows were recorded live.
ALTER TABLE consultations ADD COLUMN mode VARCHAR(12) NOT NULL DEFAULT 'LIVE';

-- The recording of the conversation, kept beside its timestamped transcript: a stored file
-- (owner_type CONSULTATION_AUDIO, owner_id = the consultation), when the browser recorded it,
-- and how long it is. Null when nothing was recorded or kept.
ALTER TABLE consultations ADD COLUMN audio_file_id UUID;
ALTER TABLE consultations ADD COLUMN audio_started_at TIMESTAMPTZ;
ALTER TABLE consultations ADD COLUMN audio_duration_ms BIGINT;

-- The draft invoice the save made for the acts done during the consultation, if the doctor asked for one.
ALTER TABLE consultations ADD COLUMN invoice_id UUID;
