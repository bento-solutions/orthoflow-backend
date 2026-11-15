-- What an uploaded patient document is: a radiograph, an intraoral scan, or another
-- picture. The dossier's overview shows the latest X-ray and intraoral scan beside the
-- 3D chart, so it has to be able to find them. Null for every other kind of file.
ALTER TABLE files ADD COLUMN category VARCHAR(32);
ALTER TABLE files ADD CONSTRAINT chk_files_category
    CHECK (category IS NULL OR category IN ('XRAY', 'INTRAORAL_3D', 'IMAGE'));
