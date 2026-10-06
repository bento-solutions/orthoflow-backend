-- Denteam parity F6: help notes per screen, the corpus behind "Ask OrthoFlow".
--
-- practice_id NULL rows are the built-in notes shipped with the product; a clinic
-- overrides one by saving a row of its own for the same page and language. Notes
-- are documentation, never patient data, which is what makes it safe to hand
-- them to a language model.

CREATE TABLE help_notes (
    id          UUID PRIMARY KEY,
    practice_id UUID         REFERENCES practices(id),
    page_key    VARCHAR(60)  NOT NULL,
    lang        VARCHAR(2)   NOT NULL CHECK (lang IN ('fr', 'en', 'ar')),
    title       VARCHAR(160) NOT NULL,
    body        TEXT         NOT NULL,
    updated_by  UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version     BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_help_notes
    ON help_notes (COALESCE(practice_id, '00000000-0000-0000-0000-000000000000'::uuid), page_key, lang);
