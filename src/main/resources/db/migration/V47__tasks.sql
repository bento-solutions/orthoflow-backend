-- Denteam parity phase 3: tasks.
--
-- A task is assigned to a person, or to a role ("the front desk") so that it shows
-- up for everyone holding it and whoever gets to it first ticks it off. The
-- top-bar counter and the "my tasks" page read the same rows.

CREATE TABLE tasks (
    id             UUID PRIMARY KEY,
    practice_id    UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                                REFERENCES practices(id),
    title          VARCHAR(300) NOT NULL,
    description    TEXT,
    assignee_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    assignee_role  VARCHAR(20)  CHECK (assignee_role IN ('ADMIN', 'DOCTOR', 'ASSISTANT')),
    created_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    due_date       DATE,
    priority       VARCHAR(10)  NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    patient_id     UUID REFERENCES patients(id) ON DELETE CASCADE,
    status         VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    done_at        TIMESTAMPTZ,
    done_by        UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_task_one_assignee CHECK (assignee_id IS NULL OR assignee_role IS NULL)
);
CREATE INDEX idx_tasks_assignee ON tasks (practice_id, assignee_id, status, due_date);
CREATE INDEX idx_tasks_role ON tasks (practice_id, assignee_role, status, due_date);
