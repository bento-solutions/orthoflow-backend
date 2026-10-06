-- Denteam parity F6: sterilization traceability, endo kits and handpiece lubrication.
--
-- Decision D2 put endodontics and general dentistry in scope, which brings back
-- the two things the plan had first skipped: endo files with a limited number of
-- uses, and handpieces that must be lubricated. Both ride on the same item model
-- as trays and instruments, so one QR label and one state cycle serve all four.

CREATE TABLE autoclaves (
    id            UUID PRIMARY KEY,
    practice_id   UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                               REFERENCES practices(id),
    name          VARCHAR(100) NOT NULL,
    model         VARCHAR(100),
    serial_number VARCHAR(60),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_autoclave_name UNIQUE (practice_id, name)
);

-- A thing that goes through sterilization: a tray, a single instrument, a
-- handpiece, an endo kit. It carries one opaque QR token and nothing about any
-- patient, so a printed label can be read by anyone without disclosing anything.
--
-- The state cycle is READY -> USED -> DIRTY -> PROCESSED -> READY. A new item
-- starts DIRTY, not READY: nothing is assumed sterile until a cycle has said so.
CREATE TABLE sterilization_items (
    id                 UUID PRIMARY KEY,
    practice_id        UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                    REFERENCES practices(id),
    code               VARCHAR(40)  NOT NULL,
    name               VARCHAR(150) NOT NULL,
    kind               VARCHAR(12)  NOT NULL CHECK (kind IN ('TRAY', 'INSTRUMENT', 'HANDPIECE', 'ENDO_KIT')),
    qr_token           VARCHAR(32)  NOT NULL UNIQUE,
    state              VARCHAR(10)  NOT NULL DEFAULT 'DIRTY' CHECK (state IN ('READY', 'USED', 'DIRTY', 'PROCESSED')),
    state_changed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_cycle_id      UUID,
    last_used_at       TIMESTAMPTZ,
    last_lubricated_at TIMESTAMPTZ,
    serial_number      VARCHAR(60),
    notes              TEXT,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_sterilization_item_code UNIQUE (practice_id, code)
);
CREATE INDEX idx_sterilization_items_state ON sterilization_items (practice_id, state) WHERE active;

CREATE TABLE sterilization_cycles (
    id             UUID PRIMARY KEY,
    practice_id    UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                REFERENCES practices(id),
    autoclave_id   UUID         NOT NULL REFERENCES autoclaves(id),
    cycle_number   INT          NOT NULL CHECK (cycle_number > 0),
    program        VARCHAR(60)  NOT NULL,
    started_at     TIMESTAMPTZ  NOT NULL,
    finished_at    TIMESTAMPTZ,
    operator_id    UUID         REFERENCES users(id),
    control_type   VARCHAR(10)  NOT NULL DEFAULT 'CHEMICAL' CHECK (control_type IN ('CHEMICAL', 'BIOLOGICAL', 'PHYSICAL')),
    control_result VARCHAR(8)   NOT NULL DEFAULT 'PENDING' CHECK (control_result IN ('PENDING', 'PASSED', 'FAILED')),
    controlled_at  TIMESTAMPTZ,
    controlled_by  UUID         REFERENCES users(id),
    control_note   TEXT,
    notes          TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_autoclave_cycle_number UNIQUE (autoclave_id, cycle_number)
);
CREATE INDEX idx_sterilization_cycles_practice ON sterilization_cycles (practice_id, started_at DESC);

CREATE TABLE sterilization_cycle_items (
    cycle_id UUID NOT NULL REFERENCES sterilization_cycles(id) ON DELETE CASCADE,
    item_id  UUID NOT NULL REFERENCES sterilization_items(id),
    PRIMARY KEY (cycle_id, item_id)
);
CREATE INDEX idx_sterilization_cycle_items_item ON sterilization_cycle_items (item_id);

ALTER TABLE sterilization_items
    ADD CONSTRAINT fk_sterilization_items_cycle FOREIGN KEY (last_cycle_id) REFERENCES sterilization_cycles(id);

-- The register: every state change, who did it, and (for a use) on whom. A use
-- stamps the cycle the item was last sterilised in, which is what lets a failed
-- control be traced forward to the patients an item touched since.
-- Patient and appointment are SET NULL on delete: an erased patient leaves the
-- sterilization record intact and anonymous.
CREATE TABLE sterilization_events (
    id             UUID PRIMARY KEY,
    practice_id    UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                REFERENCES practices(id),
    item_id        UUID         NOT NULL REFERENCES sterilization_items(id),
    action         VARCHAR(12)  NOT NULL CHECK (action IN ('REGISTERED', 'USED', 'DIRTY', 'PROCESSED', 'RELEASED', 'RECALLED', 'LUBRICATED', 'RETIRED')),
    from_state     VARCHAR(10),
    to_state       VARCHAR(10),
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    performed_by   UUID,
    cycle_id       UUID         REFERENCES sterilization_cycles(id),
    patient_id     UUID         REFERENCES patients(id) ON DELETE SET NULL,
    appointment_id UUID         REFERENCES appointments(id) ON DELETE SET NULL,
    note           TEXT
);
CREATE INDEX idx_sterilization_events_item ON sterilization_events (item_id, occurred_at DESC);
CREATE INDEX idx_sterilization_events_patient ON sterilization_events (patient_id) WHERE patient_id IS NOT NULL;
CREATE INDEX idx_sterilization_events_appointment ON sterilization_events (appointment_id) WHERE appointment_id IS NOT NULL;
CREATE INDEX idx_sterilization_events_cycle ON sterilization_events (cycle_id) WHERE cycle_id IS NOT NULL;

-- ── Endo ────────────────────────────────────────────────────────────────
-- A file model says how many times a file of that kind may be used before it is
-- thrown away (1 for a single-use file). Each physical file in a kit counts its
-- own uses, so replacing one file does not reset the others.
CREATE TABLE endo_file_models (
    id          UUID PRIMARY KEY,
    practice_id UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                             REFERENCES practices(id),
    name        VARCHAR(100) NOT NULL,
    brand       VARCHAR(80),
    size_taper  VARCHAR(40),
    max_uses    INT          NOT NULL DEFAULT 1 CHECK (max_uses >= 1),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_endo_file_model UNIQUE (practice_id, name)
);

CREATE TABLE endo_files (
    id             UUID PRIMARY KEY,
    practice_id    UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                REFERENCES practices(id),
    model_id       UUID         NOT NULL REFERENCES endo_file_models(id),
    kit_item_id    UUID         NOT NULL REFERENCES sterilization_items(id),
    use_count      INT          NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    discarded_at   TIMESTAMPTZ,
    discard_reason VARCHAR(120),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version        BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_endo_files_kit ON endo_files (kit_item_id) WHERE discarded_at IS NULL;
