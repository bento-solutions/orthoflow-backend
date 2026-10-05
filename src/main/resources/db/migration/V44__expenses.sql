-- Denteam parity phase 2.5: expenses.
--
-- Vendor invoices already track what is bought for stock; expenses are everything
-- the clinic spends — rent, salaries, CNSS, utilities, lab fees, taxes. Validating
-- a vendor invoice creates a linked expense, which gives "expenses vs stock"
-- reconciliation for free (vendor_invoice_id is unique, so it can only link once).

CREATE TABLE expense_categories (
    id            UUID PRIMARY KEY,
    practice_id   UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                               REFERENCES practices(id),
    code          VARCHAR(40)  NOT NULL,
    name_fr       VARCHAR(120) NOT NULL,
    name_en       VARCHAR(120) NOT NULL,
    name_ar       VARCHAR(120) NOT NULL,
    -- How the monthly equation treats it: salaries and CNSS are broken out of
    -- operating costs on the income statement.
    kind          VARCHAR(12)  NOT NULL DEFAULT 'OPERATING'
                  CHECK (kind IN ('OPERATING', 'SALARY', 'SOCIAL', 'TAX', 'LAB', 'SUPPLIES')),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_expense_category UNIQUE (practice_id, code)
);

INSERT INTO expense_categories (id, code, name_fr, name_en, name_ar, kind, display_order) VALUES
 (gen_random_uuid(), 'RENT',      'Loyer',                     'Rent',               'الإيجار',           'OPERATING', 10),
 (gen_random_uuid(), 'SALARIES',  'Salaires',                  'Salaries',           'الأجور',            'SALARY',    20),
 (gen_random_uuid(), 'CNSS',      'CNSS et charges sociales',  'CNSS and social charges', 'الضمان الاجتماعي', 'SOCIAL',    30),
 (gen_random_uuid(), 'UTILITIES', 'Eau, électricité, internet','Utilities',          'الماء والكهرباء',   'OPERATING', 40),
 (gen_random_uuid(), 'LAB_FEES',  'Prothèses et laboratoire',  'Lab fees',           'المختبر',           'LAB',       50),
 (gen_random_uuid(), 'SUPPLIES',  'Fournitures et consommables','Supplies',          'المستلزمات',        'SUPPLIES',  60),
 (gen_random_uuid(), 'TAXES',     'Impôts et taxes',           'Taxes',              'الضرائب',           'TAX',       70),
 (gen_random_uuid(), 'MAINTENANCE','Maintenance et équipement','Maintenance and equipment','الصيانة والمعدات', 'OPERATING', 80),
 (gen_random_uuid(), 'MARKETING', 'Communication et publicité','Marketing',          'الإعلان',           'OPERATING', 90),
 (gen_random_uuid(), 'OTHER',     'Autres',                    'Other',              'أخرى',              'OPERATING', 99);

CREATE TABLE expenses (
    id                   UUID PRIMARY KEY,
    practice_id          UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                       REFERENCES practices(id),
    expense_date         DATE          NOT NULL,
    category_id          UUID          NOT NULL REFERENCES expense_categories(id),
    payee                VARCHAR(200),
    description          VARCHAR(500),
    amount               NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    due_date             DATE,
    paid_date            DATE,
    status               VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
    method               VARCHAR(32),
    receipt_file_id      UUID REFERENCES files(id),
    vendor_invoice_id    UUID UNIQUE REFERENCES vendor_invoices(id) ON DELETE SET NULL,
    lab_order_id         UUID,                          -- FK added with lab orders (phase 3)
    recurrence           VARCHAR(10)   NOT NULL DEFAULT 'NONE' CHECK (recurrence IN ('NONE', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    recurrence_next      DATE,                          -- next date a copy is generated
    recurrence_parent_id UUID REFERENCES expenses(id) ON DELETE SET NULL,
    notes                TEXT,
    created_by           UUID,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_paid_has_date CHECK (status <> 'PAID' OR paid_date IS NOT NULL)
);
CREATE INDEX idx_expenses_date ON expenses (practice_id, expense_date);
CREATE INDEX idx_expenses_status ON expenses (practice_id, status, due_date);
CREATE INDEX idx_expenses_recurring ON expenses (recurrence_next) WHERE recurrence <> 'NONE';
