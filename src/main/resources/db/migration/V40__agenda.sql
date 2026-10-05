-- Denteam parity phase 1.1/1.2: the front desk.
--
-- Appointment types (with colours and a bookable-online flag), a richer
-- appointment lifecycle (confirmed -> arrived -> in chair -> done, with the
-- timestamps doctor-time analytics will need), waiting rooms, absences, calendar
-- events and a waiting list. The clinic does orthodontics and general dentistry
-- (decision D2), so the seeded types cover both.

-- ── Appointment types ─────────────────────────────────────────────────
CREATE TABLE appointment_types (
    id                       UUID PRIMARY KEY,
    practice_id              UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                          REFERENCES practices(id),
    code                     VARCHAR(40)  NOT NULL,
    name_fr                  VARCHAR(120) NOT NULL,
    name_en                  VARCHAR(120) NOT NULL,
    name_ar                  VARCHAR(120) NOT NULL,
    color                    VARCHAR(9)   NOT NULL DEFAULT '#64748b',
    default_duration_minutes INT          NOT NULL DEFAULT 30 CHECK (default_duration_minutes > 0),
    bookable_online          BOOLEAN      NOT NULL DEFAULT FALSE,
    specialty_group          VARCHAR(20)  NOT NULL DEFAULT 'ANY'
                             CHECK (specialty_group IN ('ORTHODONTICS', 'GENERAL', 'ANY')),
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order            INT          NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_appointment_type_code UNIQUE (practice_id, code)
);

INSERT INTO appointment_types (id, code, name_fr, name_en, name_ar, color, default_duration_minutes, bookable_online, specialty_group, display_order)
VALUES
 (gen_random_uuid(), 'INITIAL_CONSULTATION', 'Consultation initiale',                    'Initial consultation',                'استشارة أولى',            '#2563eb', 45, TRUE,  'ANY',          10),
 (gen_random_uuid(), 'RECORDS',              'Records (photos, téléradio, empreintes)',  'Records (photos, ceph, impressions)', 'تسجيلات (صور، أشعة، طبعات)', '#7c3aed', 45, FALSE, 'ORTHODONTICS', 20),
 (gen_random_uuid(), 'BONDING',              'Collage des attaches',                     'Bracket bonding',                     'لصق الأقواس',            '#0891b2', 90, FALSE, 'ORTHODONTICS', 30),
 (gen_random_uuid(), 'ADJUSTMENT',           'Contrôle / activation',                    'Adjustment visit',                    'مراجعة / تعديل',          '#16a34a', 20, TRUE,  'ORTHODONTICS', 40),
 (gen_random_uuid(), 'EMERGENCY',            'Urgence (attache décollée, douleur)',      'Emergency (loose bracket, pain)',     'طارئ (قوس منفك، ألم)',    '#dc2626', 20, TRUE,  'ANY',          50),
 (gen_random_uuid(), 'DEBOND',               'Dépose de l''appareil',                    'Debonding',                           'نزع الجهاز',             '#ea580c', 60, FALSE, 'ORTHODONTICS', 60),
 (gen_random_uuid(), 'RETENTION_CHECK',      'Contrôle de contention',                   'Retention check',                     'مراجعة التثبيت',          '#65a30d', 20, TRUE,  'ORTHODONTICS', 70),
 (gen_random_uuid(), 'ALIGNER_DELIVERY',     'Remise de gouttières',                     'Aligner delivery',                    'تسليم الشفافات',         '#0d9488', 30, FALSE, 'ORTHODONTICS', 80),
 (gen_random_uuid(), 'CHECKUP',              'Examen / contrôle',                        'Check-up',                            'فحص / مراجعة',           '#3b82f6', 30, TRUE,  'GENERAL',      110),
 (gen_random_uuid(), 'CLEANING',             'Détartrage',                               'Scaling and polishing',               'تنظيف الجير',            '#06b6d4', 30, TRUE,  'GENERAL',      120),
 (gen_random_uuid(), 'FILLING',              'Soin conservateur (carie)',                'Filling',                             'حشو (تسوس)',             '#f59e0b', 45, TRUE,  'GENERAL',      130),
 (gen_random_uuid(), 'ROOT_CANAL',           'Traitement endodontique',                  'Root canal treatment',                'علاج العصب',             '#b45309', 60, FALSE, 'GENERAL',      140),
 (gen_random_uuid(), 'EXTRACTION',           'Extraction',                               'Extraction',                          'قلع',                    '#b91c1c', 30, FALSE, 'GENERAL',      150),
 (gen_random_uuid(), 'CROWN',                'Prothèse / couronne',                      'Crown / prosthesis',                  'تلبيسة / تعويض',         '#a16207', 60, FALSE, 'GENERAL',      160),
 (gen_random_uuid(), 'IMPLANT',              'Implant',                                  'Implant',                             'زرع',                    '#4f46e5', 60, FALSE, 'GENERAL',      170),
 (gen_random_uuid(), 'WHITENING',            'Blanchiment',                              'Whitening',                           'تبييض',                  '#a855f7', 60, TRUE,  'GENERAL',      180),
 (gen_random_uuid(), 'PEDIATRIC',            'Soins enfant',                             'Paediatric care',                     'علاج الأطفال',           '#ec4899', 30, TRUE,  'GENERAL',      190);

-- Free-text types that already exist on appointments become types of their own
-- (named exactly as typed) so no history loses its label.
INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar, color, display_order)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000001',
       'LEGACY_' || substr(md5(lower(btrim(a.type))), 1, 10),
       btrim(a.type), btrim(a.type), btrim(a.type), '#64748b', 900
FROM (SELECT DISTINCT type FROM appointments WHERE btrim(type) <> '') a
WHERE NOT EXISTS (
    SELECT 1 FROM appointment_types t
    WHERE lower(t.name_fr) = lower(btrim(a.type)) OR lower(t.name_en) = lower(btrim(a.type)));

ALTER TABLE appointments
    ADD COLUMN appointment_type_id UUID REFERENCES appointment_types(id),
    ADD COLUMN confirmed_at  TIMESTAMPTZ,
    ADD COLUMN arrived_at    TIMESTAMPTZ,
    ADD COLUMN seated_at     TIMESTAMPTZ,
    ADD COLUMN finished_at   TIMESTAMPTZ,
    ADD COLUMN waiting_room_id UUID,
    ADD COLUMN waiting_priority INT NOT NULL DEFAULT 0;

UPDATE appointments a
SET appointment_type_id = t.id
FROM appointment_types t
WHERE a.appointment_type_id IS NULL
  AND (lower(t.name_fr) = lower(btrim(a.type)) OR lower(t.name_en) = lower(btrim(a.type)))
  AND t.practice_id = a.practice_id;

CREATE INDEX idx_appointments_type ON appointments (appointment_type_id);
CREATE INDEX idx_appointments_status_time ON appointments (status, date_time);

-- ── Waiting rooms and chairs ──────────────────────────────────────────
CREATE TABLE waiting_rooms (
    id            UUID PRIMARY KEY,
    practice_id   UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                               REFERENCES practices(id),
    name          VARCHAR(100) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
INSERT INTO waiting_rooms (id, name) VALUES ('f1000000-0000-0000-0000-000000000001', 'Salle d''attente');

ALTER TABLE appointments
    ADD CONSTRAINT fk_appointments_waiting_room FOREIGN KEY (waiting_room_id) REFERENCES waiting_rooms(id);

-- Treatment rooms are chairs; they gain an order and a colour for the occupancy grid.
ALTER TABLE chairs
    ADD COLUMN display_order INT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
UPDATE chairs SET display_order = CASE name WHEN 'Chair 1' THEN 1 WHEN 'Chair 2' THEN 2 WHEN 'Chair 3' THEN 3 ELSE 9 END;

-- ── Absences and events (both block slots) ─────────────────────────────
CREATE TABLE practitioner_absences (
    id              UUID PRIMARY KEY,
    practice_id     UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                REFERENCES practices(id),
    practitioner_id UUID        NOT NULL REFERENCES practitioners(id) ON DELETE CASCADE,
    starts_at       TIMESTAMPTZ NOT NULL,
    ends_at         TIMESTAMPTZ NOT NULL,
    reason          VARCHAR(30) NOT NULL DEFAULT 'OTHER'
                    CHECK (reason IN ('LEAVE', 'SICK', 'TRAINING', 'CONFERENCE', 'OTHER')),
    notes           TEXT,
    created_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_absence_order CHECK (starts_at < ends_at)
);
CREATE INDEX idx_absences_range ON practitioner_absences (practice_id, starts_at, ends_at);

CREATE TABLE calendar_events (
    id              UUID PRIMARY KEY,
    practice_id     UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                 REFERENCES practices(id),
    title           VARCHAR(200) NOT NULL,
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    chair_id        UUID REFERENCES chairs(id),
    practitioner_id UUID REFERENCES practitioners(id),
    color           VARCHAR(9)   NOT NULL DEFAULT '#475569',
    notes           TEXT,
    created_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_event_order CHECK (starts_at < ends_at)
);
CREATE INDEX idx_events_range ON calendar_events (practice_id, starts_at, ends_at);

-- ── Waiting list / unscheduled appointments ────────────────────────────
-- An unscheduled appointment is an entry with a type and a duration and no time.
CREATE TABLE waiting_list_entries (
    id                       UUID PRIMARY KEY,
    practice_id              UUID        NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                         REFERENCES practices(id),
    patient_id               UUID        NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    appointment_type_id      UUID REFERENCES appointment_types(id),
    practitioner_id          UUID REFERENCES practitioners(id),
    duration_minutes         INT         NOT NULL DEFAULT 30 CHECK (duration_minutes > 0),
    preferred_weekdays       VARCHAR(20),               -- ISO weekdays, e.g. "1,3,5"; null = any
    preferred_from           TIME,
    preferred_to             TIME,
    urgency                  VARCHAR(10) NOT NULL DEFAULT 'NORMAL'
                             CHECK (urgency IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    notes                    TEXT,
    status                   VARCHAR(12) NOT NULL DEFAULT 'WAITING'
                             CHECK (status IN ('WAITING', 'SCHEDULED', 'CANCELLED')),
    scheduled_appointment_id UUID REFERENCES appointments(id) ON DELETE SET NULL,
    created_by               UUID REFERENCES users(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_waiting_list_open ON waiting_list_entries (practice_id, status, urgency);

-- ── Status colours (configurable per clinic) ───────────────────────────
ALTER TABLE practice_settings ADD COLUMN status_colors JSONB;
