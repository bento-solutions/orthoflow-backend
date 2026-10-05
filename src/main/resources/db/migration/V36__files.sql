-- Denteam parity F5: file storage metadata. Bytes live behind StorageService
-- (local volume now, S3-compatible later); this table is the index.
-- Files are served only through authenticated endpoints, never a public bucket.

CREATE TABLE files (
    id            UUID PRIMARY KEY,
    practice_id   UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
                               REFERENCES practices(id),
    owner_type    VARCHAR(40)  NOT NULL,           -- PATIENT_PHOTO, PRACTICE_LOGO, EXPENSE_RECEIPT, LAB_ORDER, ...
    owner_id      UUID,
    original_name VARCHAR(255) NOT NULL,
    content_type  VARCHAR(120) NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes >= 0),
    sha256        VARCHAR(64)  NOT NULL,
    storage_key   VARCHAR(255) NOT NULL UNIQUE,
    uploaded_by   UUID REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX idx_files_owner ON files (owner_type, owner_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_files_practice ON files (practice_id);

ALTER TABLE practices ADD CONSTRAINT fk_practices_logo FOREIGN KEY (logo_file_id) REFERENCES files(id);
