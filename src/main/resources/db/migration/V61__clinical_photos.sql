-- Clinical photos: the standard orthodontic record a doctor takes at the start of a
-- treatment, during it and at the end, so the before and after can be compared.
--
-- * clinical_photo_series: one sitting (initial records, a progress check, the end of
--   treatment, retention), dated. A patient has as many as they have sittings.
-- * clinical_photos: one picture per standard view within a sitting. The eight
--   views are the usual extra- and intra-oral set (three of the face, two occlusal,
--   three of the bite); the panoramic and the lateral cephalogram are the two
--   radiographs an orthodontic diagnosis starts from. Replacing a view keeps one
--   row, which is why the pair (series, view) is unique.
--
-- The bytes are stored files (owner_type CLINICAL_PHOTO, owner_id = the patient),
-- served by /files/{id} under the clinical-record permission. Erasing the patient
-- removes both these rows and the files (ClinicalPhotoErasureListener).

CREATE TABLE clinical_photo_series (
    id          UUID        PRIMARY KEY,
    practice_id UUID        NOT NULL REFERENCES practices(id),
    patient_id  UUID        NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    stage       VARCHAR(20) NOT NULL CHECK (stage IN ('INITIAL', 'PROGRESS', 'FINAL', 'RETENTION')),
    taken_on    DATE        NOT NULL,
    note        TEXT,
    created_by  UUID        REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_photo_series_patient ON clinical_photo_series (practice_id, patient_id, taken_on DESC);

CREATE TABLE clinical_photos (
    id          UUID        PRIMARY KEY,
    practice_id UUID        NOT NULL REFERENCES practices(id),
    series_id   UUID        NOT NULL REFERENCES clinical_photo_series(id) ON DELETE CASCADE,
    view_type   VARCHAR(30) NOT NULL CHECK (view_type IN (
                    'SMILE', 'FACE_AT_REST', 'PROFILE',
                    'UPPER_OCCLUSAL', 'LOWER_OCCLUSAL',
                    'LEFT_LATERAL', 'FRONTAL_OCCLUSION', 'RIGHT_LATERAL',
                    'PANORAMIC_XRAY', 'LATERAL_CEPHALOGRAM')),
    file_id     UUID        NOT NULL REFERENCES files(id),
    uploaded_by UUID        REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (series_id, view_type)
);
CREATE INDEX idx_clinical_photos_practice ON clinical_photos (practice_id);
