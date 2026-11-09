-- Whether the request came from a contact the clinic can vouch for (a patient's
-- number, or one that wrote to the clinic's WhatsApp) when it was made. The
-- clinic-wide cap on waiting requests counts only the others, so a script
-- inventing numbers can no longer close online booking for real patients
-- (audit S6).
ALTER TABLE booking_requests ADD COLUMN proven BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX idx_booking_requests_pending ON booking_requests (practice_id, proven) WHERE status = 'PENDING';
