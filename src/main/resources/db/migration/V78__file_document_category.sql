-- What a patient document is (X-ray, 3D intraoral scan, other image), chosen at upload.
-- Nullable: older files and every other owner type have none.
ALTER TABLE files ADD COLUMN category VARCHAR(32);
CREATE INDEX idx_files_owner_category ON files (owner_type, owner_id, category) WHERE deleted_at IS NULL;
