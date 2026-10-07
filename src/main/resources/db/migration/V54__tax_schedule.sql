-- The income-tax schedule a clinic enters for the simulator.
-- No rates are built in: the figures differ by year and by taxpayer, and a wrong one in a
-- simulation is worse than none. The clinic (or its accountant) enters the bands and says where
-- they come from; the application only does the arithmetic.
CREATE TABLE practice_tax_schedules (
    id                  UUID PRIMARY KEY,
    practice_id         UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                      REFERENCES practices(id),
    tax_year            SMALLINT      NOT NULL CHECK (tax_year BETWEEN 2000 AND 2100),
    -- Where these figures come from (the text of law and its year): shown beside every result.
    source              VARCHAR(500)  NOT NULL CHECK (length(btrim(source)) > 0),
    -- Taken off the tax, per dependent, up to max_dependents.
    dependent_deduction NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (dependent_deduction >= 0),
    max_dependents      SMALLINT      NOT NULL DEFAULT 0 CHECK (max_dependents BETWEEN 0 AND 20),
    updated_by          UUID,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_practice_tax_year UNIQUE (practice_id, tax_year)
);

-- Marginal bands, lowest first. A NULL upper limit is the open-ended top band.
CREATE TABLE practice_tax_brackets (
    schedule_id  UUID          NOT NULL REFERENCES practice_tax_schedules(id) ON DELETE CASCADE,
    position     SMALLINT      NOT NULL CHECK (position >= 0),
    up_to        NUMERIC(14,2) CHECK (up_to IS NULL OR up_to > 0),
    rate_percent NUMERIC(5,2)  NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    PRIMARY KEY (schedule_id, position)
);
