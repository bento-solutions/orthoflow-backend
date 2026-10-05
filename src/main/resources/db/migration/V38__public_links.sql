-- Denteam parity F7: tokens for the unauthenticated /public/** surface (online
-- booking, self-registration, satisfaction surveys). Only a hash of the token is
-- stored, so a database read never yields a usable link. A link is
-- single-purpose, expires, and may be capped in uses. Clinic-wide links
-- (the booking page, the registration form) are SHARED: their token is derived
-- from a server secret plus the rotation, so staff can copy the link again
-- later without the raw token ever being stored.

CREATE TABLE public_links (
    id           UUID PRIMARY KEY,
    practice_id  UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                             REFERENCES practices(id),
    purpose      VARCHAR(30) NOT NULL CHECK (purpose IN ('BOOKING', 'REGISTRATION', 'SURVEY')),
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    subject_type VARCHAR(40),            -- APPOINTMENT for a survey, PATIENT for an invite, SHARED for a clinic-wide link
    subject_id   UUID,
    expires_at   TIMESTAMPTZ,            -- null = no expiry (the shared clinic-wide links)
    max_uses     INT,                    -- null = unlimited
    uses         INT         NOT NULL DEFAULT 0,
    rotation     INT         NOT NULL DEFAULT 0,   -- a SHARED link's token is derived from this; rotating it replaces the link
    revoked_at   TIMESTAMPTZ,
    created_by   UUID REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_public_links_subject ON public_links (subject_type, subject_id);
