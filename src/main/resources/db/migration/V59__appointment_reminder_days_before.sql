-- How many days before a visit its reminder goes out. 1 is what the clinic had until now
-- (the reminder goes out the day before); 0 is the morning of the visit.
ALTER TABLE messaging_settings
    ADD COLUMN appointment_reminder_days_before SMALLINT NOT NULL DEFAULT 1
        CHECK (appointment_reminder_days_before BETWEEN 0 AND 14);
