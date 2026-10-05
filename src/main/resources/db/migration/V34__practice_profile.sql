-- Denteam parity F2: the clinic's legal identity moves from every staff PC's
-- localStorage (CabinetService) to the server, so a document printed from any
-- machine carries the same ICE, IF, patente and RIB.
--
-- The profile lives on practices (V32) rather than practice_settings: it
-- identifies the clinic, while practice_settings keeps tunable behaviour.

ALTER TABLE practices
    ADD COLUMN legal_name  VARCHAR(200),
    ADD COLUMN ice         VARCHAR(30),
    ADD COLUMN tax_id      VARCHAR(30),       -- identifiant fiscal (IF)
    ADD COLUMN patente     VARCHAR(30),
    ADD COLUMN cnss_number VARCHAR(30),
    ADD COLUMN rib         VARCHAR(40),
    ADD COLUMN inpe        VARCHAR(20),
    ADD COLUMN address     TEXT,
    ADD COLUMN city        VARCHAR(100),
    ADD COLUMN phone       VARCHAR(40),
    ADD COLUMN email       VARCHAR(255),
    ADD COLUMN website     VARCHAR(255),
    ADD COLUMN logo_file_id UUID,
    ADD COLUMN currency    VARCHAR(3)  NOT NULL DEFAULT 'MAD',
    ADD COLUMN timezone    VARCHAR(50) NOT NULL DEFAULT 'Africa/Casablanca',
    ADD COLUMN default_language VARCHAR(2) NOT NULL DEFAULT 'fr';

-- Per-weekday opening hours, replacing the single clinic-wide start/end hour.
-- weekday follows ISO-8601: 1 = Monday ... 7 = Sunday. The agenda and the
-- online-booking slot search both read this table.
CREATE TABLE practice_opening_hours (
    id           UUID PRIMARY KEY,
    practice_id  UUID     NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                          REFERENCES practices(id) ON DELETE CASCADE,
    weekday      SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    closed       BOOLEAN  NOT NULL DEFAULT FALSE,
    open_time    TIME     NOT NULL DEFAULT '08:00',
    close_time   TIME     NOT NULL DEFAULT '19:00',
    break_start  TIME,
    break_end    TIME,
    CONSTRAINT uq_opening_hours_day UNIQUE (practice_id, weekday),
    CONSTRAINT chk_opening_order CHECK (closed OR open_time < close_time),
    CONSTRAINT chk_break_order CHECK (
        (break_start IS NULL AND break_end IS NULL)
        OR (break_start IS NOT NULL AND break_end IS NOT NULL
            AND break_start < break_end AND break_start > open_time AND break_end < close_time))
);

-- Seed from the existing clinic-wide hours: Monday-Saturday open, Sunday closed
-- (the usual Moroccan clinic week); an admin adjusts it in Settings.
INSERT INTO practice_opening_hours (id, practice_id, weekday, closed, open_time, close_time)
SELECT gen_random_uuid(), s.practice_id, d.weekday, d.weekday = 7,
       (s.working_hours_start::text || ':00')::time,
       (s.working_hours_end::text || ':00')::time
FROM practice_settings s
CROSS JOIN generate_series(1, 7) AS d(weekday);
