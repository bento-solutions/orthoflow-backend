-- Configure daily 20:00 reminder default in clinic timezone.
ALTER TABLE messaging_settings ALTER COLUMN reminder_send_hour SET DEFAULT 20;
ALTER TABLE messaging_settings ALTER COLUMN appointment_reminders SET DEFAULT TRUE;

UPDATE messaging_settings
SET reminder_send_hour = 20,
    appointment_reminders = TRUE
WHERE reminder_send_hour = 17;
