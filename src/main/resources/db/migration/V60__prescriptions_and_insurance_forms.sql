-- Ordonnances, and the patient's own insurer's care form filled at the end of a session.

-- ── Prescription library ────────────────────────────────────────────────────
-- The dental and oral prescriptions published by ordonnance.ma (research/
-- ordonnance-ma-dental-prescriptions.md, scraped 2026-10-09). Educational content
-- the site says does not replace a doctor's judgement: national reference data the
-- same for every clinic, so no practice_id, and never used as-is. A clinic adopts an
-- entry as its own template, a doctor reads and validates it, and only then does it
-- appear without its "to review" flag. Doses are the site's, unchanged; where its
-- pages disagree (paracetamol: 3 g/day on the dental pages, 4 g/day on the pain page)
-- both are kept as published and the doctor decides.
CREATE TABLE prescription_library (
    code          VARCHAR(40)  PRIMARY KEY,
    name          VARCHAR(160) NOT NULL,
    category      VARCHAR(20)  NOT NULL CHECK (category IN ('DENTAL', 'MUCOSA', 'PAIN')),
    lines         JSONB        NOT NULL,
    advice        TEXT,
    warning_signs TEXT,
    alternative   TEXT,
    source_url    TEXT         NOT NULL,
    sort_order    INT          NOT NULL
);

INSERT INTO prescription_library (code, name, category, lines, advice, warning_signs, alternative, source_url, sort_order) VALUES
('abces-dentaire', 'Abcès dentaire', 'DENTAL',
 $j$[{"drug":"AUGMENTIN 1 G/125 MG","form":"Sachet","dci":"Amoxicilline + acide clavulanique","posology":"1 sachet 3 fois par jour pendant 7 jours"},
     {"drug":"HEXIDYL","form":"Bain de bouche","dci":"Hexétidine","posology":"3 fois par jour pendant 10 jours"}]$j$,
 'Le geste dentaire (drainage, soin de la dent) est le traitement essentiel ; l''antibiotique seul ne guérit pas.',
 'Fièvre > 38,5 °C, œdème diffus de la face ou du cou, trismus, difficulté à avaler ou à respirer, immunodépression, extension rapide.',
 'Allergie aux bêta-lactamines : PYOSTACINE 500 mg, 2 comprimés 3 fois par jour pendant 7 jours.',
 'https://ordonnance.ma/ordonnance.php?id=62', 10),
('alveolite', 'Alvéolite dentaire post-extractionnelle', 'DENTAL',
 $j$[{"drug":"DOLIPRANE 1 G","form":"Comprimé","dci":"Paracétamol","posology":"1 comprimé toutes les 8 heures si douleur, maximum 3 g par jour"},
     {"drug":"BRUFEN 400 MG","form":"Comprimé","dci":"Ibuprofène","posology":"1 comprimé matin et soir pendant les repas, 2 à 3 jours"},
     {"drug":"ELUDRIL","form":"Bain de bouche","dci":"Chlorhexidine + chlorobutanol","posology":"1 bain de bouche matin et soir, doucement, 5 à 7 jours"}]$j$,
 'Pas d''antibiotique systématique sans signe infectieux. Soin local : irrigation douce et pansement alvéolaire. Ne pas fumer, ne pas cracher fort, pas de bain de bouche vigoureux les 24 premières heures, alimentation molle et tiède. Ibuprofène à éviter en cas de grossesse, d''ulcère, d''insuffisance rénale, d''anticoagulants ou d''allergie aux AINS.',
 'Fièvre, gonflement du visage, trismus, pus abondant, cellulite, immunodépression, diabète déséquilibré.',
 NULL, 'https://ordonnance.ma/ordonnance.php?id=326', 20),
('cellulite-debutante', 'Cellulite dentaire débutante sans signe de gravité', 'DENTAL',
 $j$[{"drug":"CO-AMOXICLAV 1 G/125 MG","form":"Sachet","dci":"Amoxicilline + acide clavulanique","posology":"1 sachet matin et soir au repas pendant 7 jours"},
     {"drug":"DOLIPRANE 1 G","form":"Comprimé","dci":"Paracétamol","posology":"1 comprimé toutes les 8 heures si douleur, maximum 3 g par jour"},
     {"drug":"ELUDRIL","form":"Bain de bouche","dci":"Chlorhexidine + chlorobutanol","posology":"1 bain de bouche matin et soir après le brossage, 5 à 7 jours, ne pas avaler"}]$j$,
 'Consultation dentaire sous 24 à 48 heures. Ne pas chauffer la joue. Éviter les AINS en automédication en cas d''infection dentaire.',
 'Urgence hospitalière : fièvre élevée, trismus, difficulté à avaler ou à respirer, atteinte du plancher buccal, cellulite cervicale.',
 NULL, 'https://ordonnance.ma/ordonnance.php?id=325', 30),
('pericoronarite', 'Péricoronarite aiguë non compliquée de l''adulte', 'DENTAL',
 $j$[{"drug":"ELUDRIL","form":"Bain de bouche","dci":"Chlorhexidine","posology":"1 bain de bouche matin et soir après le brossage, garder 1 minute puis recracher, 5 à 7 jours"},
     {"drug":"DOLIPRANE 1 G","form":"Comprimé","dci":"Paracétamol","posology":"1 comprimé toutes les 8 heures si douleur, maximum 3 g par jour, 2 à 3 jours"},
     {"drug":"AUGMENTIN 1 G/125 MG","form":"Sachet","dci":"Amoxicilline + acide clavulanique","posology":"1 prise matin et soir au repas pendant 5 jours, seulement si douleur importante, inflammation étendue, trismus débutant, adénopathie ou signes infectieux marqués"}]$j$,
 'Capuchon muqueux d''une dent de sagesse inférieure : avulsion à distance de l''épisode aigu.',
 NULL, NULL, 'https://ordonnance.ma/ordonnance.php?id=298', 40),
('gingivite-ulcero-necrotique', 'Gingivite ulcéro-nécrotique aiguë', 'DENTAL',
 $j$[{"drug":"FLAGYL 500 MG","form":"Comprimé","dci":"Métronidazole","posology":"1 comprimé matin, midi et soir pendant 3 jours ; pas d'alcool pendant le traitement et 24 à 48 heures après"},
     {"drug":"ELUDRIL","form":"Bain de bouche","dci":"Chlorhexidine","posology":"1 bain de bouche matin et soir, garder 1 minute puis recracher, 5 à 7 jours"},
     {"drug":"DOLIPRANE 1 G","form":"Comprimé","dci":"Paracétamol","posology":"1 comprimé toutes les 8 heures si douleur, maximum 3 g par jour"}]$j$,
 NULL, NULL, NULL, 'https://ordonnance.ma/ordonnance.php?id=299', 50),
('sialadenite', 'Sialadénite bactérienne aiguë non compliquée', 'DENTAL',
 $j$[{"drug":"AUGMENTIN 1 G/125 MG","form":"Sachet","dci":"Amoxicilline + acide clavulanique","posology":"1 prise matin et soir au repas pendant 7 jours"},
     {"drug":"DOLIPRANE 1 G","form":"Comprimé","dci":"Paracétamol","posology":"1 comprimé toutes les 8 heures si douleur ou fièvre, maximum 3 g par jour, 2 à 3 jours"},
     {"drug":"ELUDRIL","form":"Bain de bouche","dci":"Chlorhexidine","posology":"1 bain de bouche matin et soir pendant 5 jours, si mauvaise hygiène buccale ou gingivite associée"}]$j$,
 NULL, NULL, NULL, 'https://ordonnance.ma/ordonnance.php?id=300', 60),
('aphtes', 'Aphtes', 'MUCOSA',
 $j$[{"drug":"OROPROPOLIS","form":"Spray buccal","dci":"Glycérine + propolis","posology":"1 application 4 fois par jour"}]$j$,
 NULL, NULL, NULL, 'https://ordonnance.ma/ordonnance.php?id=29', 70),
('candidose-buccale', 'Candidose buccale', 'MUCOSA',
 $j$[{"drug":"MYCOPHARM","form":"Suspension buvable","dci":"Nystatine","posology":"1 mL (100 000 UI) 4 fois par jour, à garder en bouche le plus longtemps possible avant d'avaler, à distance des repas, 1 à 2 semaines (poursuivre 48 heures après la guérison)"}]$j$,
 'Utile chez les porteurs d''appareils amovibles ou de gouttières.', NULL, NULL,
 'https://ordonnance.ma/ordonnance.php?id=81', 80),
('bouche-seche', 'Bouche sèche', 'MUCOSA',
 $j$[{"drug":"MIRADENT AQUAMED MUNDSPRAY","form":"Spray buccal","dci":"Salive artificielle","posology":"1 à 2 pulvérisations 4 à 6 fois par jour selon le besoin"}]$j$,
 NULL, NULL, NULL, 'https://ordonnance.ma/ordonnance.php?id=180', 90),
('herpes-labial', 'Herpès labial récidivant', 'MUCOSA',
 $j$[{"drug":"VALEX 500 MG","form":"Comprimé","dci":"Valaciclovir","posology":"4 comprimés en une prise dès les premiers signes, puis 4 comprimés 12 heures après (1 jour, maximum 8 comprimés)"},
     {"drug":"ZOVIRAX 5 %","form":"Crème","dci":"Aciclovir","posology":"5 applications par jour pendant 5 jours ; se laver les mains avant et après"}]$j$,
 NULL, NULL, NULL, 'https://ordonnance.ma/ordonnance.php?id=323', 100),
('douleur-palier-1', 'Douleur aiguë — palier 1', 'PAIN',
 $j$[{"drug":"ANDOL 1000 MG","form":"Comprimé","dci":"Paracétamol","posology":"1 comprimé 1 à 4 fois par jour, toutes les 6 heures, maximum 4 g par jour"}]$j$,
 NULL, NULL, NULL, 'https://ordonnance.ma/ordonnance.php?id=31', 110),
('douleur-palier-2', 'Douleur aiguë — palier 2', 'PAIN',
 $j$[{"drug":"CODOLIPRANE 400/20 MG","form":"Comprimé","dci":"Paracétamol + codéine","posology":"1 à 2 comprimés 1 à 3 fois par jour, maximum 6 comprimés par jour"}]$j$,
 NULL, NULL, NULL, 'https://ordonnance.ma/ordonnance.php?id=32', 120);

-- ── A clinic's own prescription templates ───────────────────────────────────
CREATE TABLE prescription_templates (
    id           UUID PRIMARY KEY,
    practice_id  UUID         NOT NULL REFERENCES practices(id),
    name         VARCHAR(160) NOT NULL,
    category     VARCHAR(20)  NOT NULL DEFAULT 'OTHER'
                              CHECK (category IN ('DENTAL', 'MUCOSA', 'PAIN', 'ORTHO', 'OTHER')),
    lines        JSONB        NOT NULL,
    advice       TEXT,
    library_code VARCHAR(40)  REFERENCES prescription_library(code),
    reviewed_by  UUID         REFERENCES users(id),
    reviewed_at  TIMESTAMPTZ,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by   UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_prescription_template_name UNIQUE (practice_id, name)
);
CREATE INDEX idx_prescription_templates_practice ON prescription_templates (practice_id);

-- ── Prescriptions as issued ─────────────────────────────────────────────────
CREATE TABLE prescriptions (
    id               UUID PRIMARY KEY,
    practice_id      UUID        NOT NULL REFERENCES practices(id),
    number           VARCHAR(40) NOT NULL,
    patient_id       UUID        NOT NULL REFERENCES patients(id) ON DELETE RESTRICT,
    practitioner_id  UUID        REFERENCES practitioners(id),
    consultation_id  UUID        REFERENCES consultations(id),
    template_id      UUID        REFERENCES prescription_templates(id) ON DELETE SET NULL,
    lines            JSONB       NOT NULL,
    advice           TEXT,
    -- The allergy warnings the prescriber saw and went past, kept with what was printed.
    allergy_warnings JSONB,
    status           VARCHAR(10) NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED', 'VOID')),
    file_id          UUID        REFERENCES files(id),
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       UUID,
    CONSTRAINT uq_prescription_number UNIQUE (practice_id, number)
);
CREATE INDEX idx_prescriptions_patient ON prescriptions (patient_id, issued_at DESC);
CREATE INDEX idx_prescriptions_practice ON prescriptions (practice_id, issued_at DESC);

-- ── Which paper form an insurer's patients need ─────────────────────────────
-- Null: the form OrthoFlow knows for the insurer's code (the public-sector mutuals
-- file the CNOPS sheet), else the generic statement of acts. A clinic may override it.
ALTER TABLE insurers ADD COLUMN form_code VARCHAR(40);

INSERT INTO insurers (id, practice_id, code, name, kind)
SELECT gen_random_uuid(), p.id, v.code, v.name, v.kind
FROM practices p CROSS JOIN (VALUES
    ('OMFAM', 'OMFAM', 'PUBLIC'),
    ('MGEN', 'MGEN', 'PUBLIC'),
    ('MGPTT', 'MGPTT', 'PUBLIC'),
    ('MDII', 'MDII (Douanes et impôts indirects)', 'PUBLIC'),
    ('LAMAS', 'LA MAS', 'PRIVATE'),
    ('CMIM', 'CMIM', 'PRIVATE'),
    ('SANLAM', 'Sanlam Maroc', 'PRIVATE'),
    ('ALLIANZ', 'Allianz Maroc', 'PRIVATE'),
    ('MAROCAINE_VIE', 'La Marocaine Vie', 'PRIVATE'),
    ('MAMDA', 'MAMDA', 'PRIVATE'),
    ('MCMA', 'MCMA', 'PRIVATE')
) AS v(code, name, kind)
ON CONFLICT (practice_id, code) DO NOTHING;

-- ── What the forms ask about the insured person ─────────────────────────────
-- insurance_number stays the immatriculation (matricule); the affiliation number is
-- the CNOPS one. When the patient is the insured's spouse or child, the forms want
-- the insured's own name and CIN in the "assuré" part.
ALTER TABLE patients
    ADD COLUMN insurance_affiliation_number VARCHAR(50),
    ADD COLUMN insured_relation             VARCHAR(10) NOT NULL DEFAULT 'SELF'
                                            CHECK (insured_relation IN ('SELF', 'SPOUSE', 'CHILD')),
    ADD COLUMN insured_name                 VARCHAR(255),
    ADD COLUMN insured_cin                  VARCHAR(50);

-- ── Care forms filled for the patient's insurer ─────────────────────────────
CREATE TABLE insurance_forms (
    id              UUID PRIMARY KEY,
    practice_id     UUID          NOT NULL REFERENCES practices(id),
    number          VARCHAR(40)   NOT NULL,
    patient_id      UUID          NOT NULL REFERENCES patients(id) ON DELETE RESTRICT,
    insurer_id      UUID          REFERENCES insurers(id),
    -- The layout the PDF was drawn with ('generic' for OrthoFlow's own statement).
    form_code       VARCHAR(40)   NOT NULL,
    purpose         VARCHAR(16)   NOT NULL CHECK (purpose IN ('EXECUTION', 'PRIOR_AGREEMENT')),
    source          VARCHAR(16)   NOT NULL CHECK (source IN ('CONSULTATION', 'MANUAL')),
    consultation_id UUID          REFERENCES consultations(id),
    practitioner_id UUID          REFERENCES practitioners(id),
    care_date       DATE          NOT NULL,
    lines           JSONB         NOT NULL,
    total           NUMERIC(12,2) NOT NULL,
    -- The patient, insured person and practitioner exactly as printed.
    snapshot        JSONB         NOT NULL,
    status          VARCHAR(12)   NOT NULL DEFAULT 'TO_PRINT'
                                  CHECK (status IN ('TO_PRINT', 'PRINTED', 'HANDED_OVER', 'VOID')),
    file_id         UUID          REFERENCES files(id),
    task_id         UUID          REFERENCES tasks(id) ON DELETE SET NULL,
    printed_at      TIMESTAMPTZ,
    handed_over_at  TIMESTAMPTZ,
    notes           TEXT,
    created_by      UUID,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_insurance_form_number UNIQUE (practice_id, number)
);
CREATE INDEX idx_insurance_forms_patient ON insurance_forms (patient_id, created_at DESC);
CREATE INDEX idx_insurance_forms_status ON insurance_forms (practice_id, status, created_at DESC);
CREATE INDEX idx_insurance_forms_consultation ON insurance_forms (consultation_id);

-- ── A task can point at the document it is about ────────────────────────────
ALTER TABLE tasks
    ADD COLUMN document_kind VARCHAR(20) CHECK (document_kind IN ('INSURANCE_FORM', 'PRESCRIPTION')),
    ADD COLUMN document_id   UUID,
    ADD CONSTRAINT ck_tasks_document CHECK ((document_kind IS NULL) = (document_id IS NULL));
