package com.orthoflow.imaging.infrastructure;

import com.orthoflow.patient.application.port.PatientErasureListener;
import com.orthoflow.storage.application.port.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Photos of a patient's face and mouth are theirs: erasure removes the series, the
 * rows that place each picture and the stored files, bytes included.
 */
@Component
@RequiredArgsConstructor
public class ClinicalPhotoErasureListener implements PatientErasureListener {

    private static final Logger log = LoggerFactory.getLogger(ClinicalPhotoErasureListener.class);

    private final JdbcTemplate jdbc;
    private final StorageService storage;

    @Override
    public void onPatientErased(UUID patientId) {
        // The photo rows reference the files, so they go first; the series cascade to them anyway.
        jdbc.update("DELETE FROM clinical_photo_series WHERE patient_id = ?", patientId);
        List<String> keys = jdbc.queryForList("SELECT storage_key FROM files WHERE owner_id = ? AND owner_type = 'CLINICAL_PHOTO'",
                String.class, patientId);
        jdbc.update("DELETE FROM files WHERE owner_id = ? AND owner_type = 'CLINICAL_PHOTO'", patientId);
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (IOException e) {
                // As for the other stored files: the rows are gone, a stray file is an operator's job, not a reason to keep the data.
                log.error("Could not delete the stored photo {} of erased patient {}", key, patientId, e);
            }
        }
    }
}
