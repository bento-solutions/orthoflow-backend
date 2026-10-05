-- Denteam parity phase 3: lab orders.
--
-- Orthodontics lives on the lab: aligners, retainers, expanders, models. An order
-- records what was sent, when it is due, what it costs and which appointment it is
-- for — the link that lets the system warn when a fitting is booked before the
-- piece is due back. Labs are suppliers with a different kind, so the supplier
-- table, its screens and its purchase-order history are reused rather than copied.

ALTER TABLE suppliers ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'SUPPLIER' CHECK (kind IN ('SUPPLIER', 'LAB'));

CREATE TABLE lab_orders (
    id                     UUID PRIMARY KEY,
    practice_id            UUID          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                         REFERENCES practices(id),
    patient_id             UUID          NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    lab_id                 UUID          NOT NULL REFERENCES suppliers(id),
    practitioner_id        UUID REFERENCES practitioners(id),
    item_type              VARCHAR(20)   NOT NULL
                           CHECK (item_type IN ('ALIGNER', 'RETAINER', 'EXPANDER', 'MODEL', 'CROWN', 'BRIDGE', 'DENTURE', 'NIGHTGUARD', 'OTHER')),
    description            VARCHAR(500),
    sent_date              DATE,
    due_date               DATE,
    status                 VARCHAR(12)   NOT NULL DEFAULT 'SENT'
                           CHECK (status IN ('SENT', 'IN_PROGRESS', 'RECEIVED', 'FITTED', 'REMAKE')),
    urgent                 BOOLEAN       NOT NULL DEFAULT FALSE,
    cost                   NUMERIC(12,2) CHECK (cost IS NULL OR cost >= 0),
    fitting_appointment_id UUID REFERENCES appointments(id) ON DELETE SET NULL,
    received_date          DATE,
    fitted_date            DATE,
    notes                  TEXT,
    created_by             UUID,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version                BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_lab_orders_status_due ON lab_orders (practice_id, status, due_date);
CREATE INDEX idx_lab_orders_patient ON lab_orders (patient_id);
CREATE INDEX idx_lab_orders_updated ON lab_orders (practice_id, updated_at);

-- The lab fee booked as an expense when the piece arrives; one per order.
ALTER TABLE expenses ADD CONSTRAINT fk_expenses_lab_order FOREIGN KEY (lab_order_id) REFERENCES lab_orders(id) ON DELETE SET NULL;
CREATE UNIQUE INDEX uq_expenses_lab_order ON expenses (lab_order_id) WHERE lab_order_id IS NOT NULL;
