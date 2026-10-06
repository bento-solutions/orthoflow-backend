-- Denteam parity phase 4: reminders, online booking, self-registration, surveys.
--
-- Everything here that talks to a patient is off until the clinic switches it on.

-- ── Reminder and survey settings (one row per clinic) ──────────────────
CREATE TABLE messaging_settings (
    practice_id           UUID PRIMARY KEY REFERENCES practices(id) ON DELETE CASCADE,
    appointment_reminders BOOLEAN  NOT NULL DEFAULT FALSE,
    reminder_send_hour    SMALLINT NOT NULL DEFAULT 17 CHECK (reminder_send_hour BETWEEN 0 AND 23),
    instalment_reminders  BOOLEAN  NOT NULL DEFAULT FALSE,
    instalment_days_before INT     NOT NULL DEFAULT 2 CHECK (instalment_days_before BETWEEN 0 AND 30),
    survey_enabled        BOOLEAN  NOT NULL DEFAULT FALSE,
    survey_delay_hours    INT      NOT NULL DEFAULT 3 CHECK (survey_delay_hours BETWEEN 0 AND 168),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO messaging_settings (practice_id) SELECT id FROM practices;

-- ── Online booking ─────────────────────────────────────────────────────
CREATE TABLE booking_settings (
    practice_id       UUID PRIMARY KEY REFERENCES practices(id) ON DELETE CASCADE,
    enabled           BOOLEAN  NOT NULL DEFAULT FALSE,
    lead_time_hours   INT      NOT NULL DEFAULT 24 CHECK (lead_time_hours >= 0),
    max_days_ahead    INT      NOT NULL DEFAULT 60 CHECK (max_days_ahead BETWEEN 1 AND 365),
    slot_step_minutes INT      NOT NULL DEFAULT 15 CHECK (slot_step_minutes IN (5, 10, 15, 20, 30, 60)),
    auto_confirm      BOOLEAN  NOT NULL DEFAULT FALSE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO booking_settings (practice_id) SELECT id FROM practices;

CREATE TABLE booking_requests (
    id                  UUID PRIMARY KEY,
    practice_id         UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                     REFERENCES practices(id),
    appointment_type_id UUID         NOT NULL REFERENCES appointment_types(id),
    practitioner_id     UUID REFERENCES practitioners(id),
    starts_at           TIMESTAMPTZ  NOT NULL,
    duration_minutes    INT          NOT NULL CHECK (duration_minutes > 0),
    first_name          VARCHAR(255) NOT NULL,
    last_name           VARCHAR(255) NOT NULL,
    phone               VARCHAR(40),
    email               VARCHAR(255),
    date_of_birth       DATE,
    note                TEXT,
    language            VARCHAR(2)   NOT NULL DEFAULT 'fr',
    status              VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'DECLINED', 'EXPIRED')),
    patient_id          UUID REFERENCES patients(id) ON DELETE SET NULL,
    appointment_id      UUID REFERENCES appointments(id) ON DELETE SET NULL,
    decline_reason      TEXT,
    decided_by          UUID,
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_booking_contact CHECK (phone IS NOT NULL OR email IS NOT NULL)
);
CREATE INDEX idx_booking_requests_open ON booking_requests (practice_id, status, starts_at);

-- ── Self-registration ──────────────────────────────────────────────────
CREATE TABLE pending_patients (
    id                 UUID PRIMARY KEY,
    practice_id        UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                    REFERENCES practices(id),
    first_name         VARCHAR(255) NOT NULL,
    last_name          VARCHAR(255) NOT NULL,
    gender             VARCHAR(1),
    date_of_birth      DATE,
    phone              VARCHAR(50),
    email              VARCHAR(255),
    address            TEXT,
    cin                VARCHAR(50),
    guardian_name      VARCHAR(255),
    guardian_phone     VARCHAR(50),
    insurance_provider VARCHAR(100),
    insurance_number   VARCHAR(100),
    occupation         VARCHAR(150),
    preferred_language VARCHAR(2)   NOT NULL DEFAULT 'fr',
    consented_at       TIMESTAMPTZ  NOT NULL,          -- Law 09-08: the person agreed on the form
    consent_whatsapp   BOOLEAN      NOT NULL DEFAULT FALSE,
    consent_email      BOOLEAN      NOT NULL DEFAULT FALSE,
    invited_patient_id UUID REFERENCES patients(id) ON DELETE SET NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    patient_id         UUID REFERENCES patients(id) ON DELETE SET NULL,
    reject_reason      TEXT,
    decided_by         UUID,
    decided_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pending_patients_open ON pending_patients (practice_id, status, created_at);

-- ── Satisfaction surveys ───────────────────────────────────────────────
CREATE TABLE satisfaction_surveys (
    id              UUID PRIMARY KEY,
    practice_id     UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                REFERENCES practices(id),
    appointment_id  UUID        NOT NULL UNIQUE REFERENCES appointments(id) ON DELETE CASCADE,
    patient_id      UUID        NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    practitioner_id UUID REFERENCES practitioners(id),
    link_id         UUID REFERENCES public_links(id) ON DELETE SET NULL,
    rating          SMALLINT    CHECK (rating BETWEEN 1 AND 5),
    comment         TEXT,
    call_me         BOOLEAN     NOT NULL DEFAULT FALSE,
    handled_at      TIMESTAMPTZ,
    handled_by      UUID,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    submitted_at    TIMESTAMPTZ
);
CREATE INDEX idx_surveys_submitted ON satisfaction_surveys (practice_id, submitted_at) WHERE submitted_at IS NOT NULL;
