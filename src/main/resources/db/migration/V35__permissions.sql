-- Denteam parity F3: permissions on top of the three fixed roles.
--
-- The set of permissions is code-defined (com.orthoflow.auth.domain.model.
-- Permission); what an admin edits is which roles hold which. A role has a row
-- in role_permission_sets once an admin has customised it; until then the
-- code-defined defaults apply. That marker is what lets an admin strip a role
-- down to nothing without the defaults silently coming back.
-- ADMIN always holds every permission and cannot be edited, so the practice
-- owner can never lock themselves out.

CREATE TABLE role_permission_sets (
    practice_id UUID        NOT NULL REFERENCES practices(id) ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL CHECK (role IN ('DOCTOR', 'ASSISTANT')),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  UUID REFERENCES users(id),
    PRIMARY KEY (practice_id, role)
);

CREATE TABLE role_permissions (
    practice_id UUID        NOT NULL,
    role        VARCHAR(20) NOT NULL,
    permission  VARCHAR(60) NOT NULL,
    PRIMARY KEY (practice_id, role, permission),
    FOREIGN KEY (practice_id, role) REFERENCES role_permission_sets (practice_id, role) ON DELETE CASCADE
);

-- Admin user management: invite, deactivate, force a reset.
ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_login_at TIMESTAMPTZ;

-- Active sessions for "My account". A session row is written at login and
-- revoked individually; JwtAuthFilter checks the jti it carries.
CREATE TABLE user_sessions (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ip          VARCHAR(64),
    user_agent  VARCHAR(255),
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX idx_user_sessions_user ON user_sessions (user_id, revoked_at);
