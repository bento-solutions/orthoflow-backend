package com.orthoflow.storage.infrastructure;

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
 * A patient's photo and uploaded documents are files on disk that no database
 * cascade reaches. Erasure deletes the bytes and the rows that describe them.
 */
@Component
@RequiredArgsConstructor
public class StorageErasureListener implements PatientErasureListener {

    private static final Logger log = LoggerFactory.getLogger(StorageErasureListener.class);

    private final JdbcTemplate jdbc;
    private final StorageService storage;

    @Override
    public void onPatientErased(UUID patientId) {
        // The patient row references its photo, so that reference goes before the file does.
        jdbc.update("UPDATE patients SET photo_file_id = NULL WHERE id = ?", patientId);
        List<String> keys = jdbc.queryForList("SELECT storage_key FROM files WHERE owner_id = ? AND owner_type IN ('PATIENT_PHOTO', 'PATIENT_DOCUMENT')",
                String.class, patientId);
        jdbc.update("DELETE FROM files WHERE owner_id = ? AND owner_type IN ('PATIENT_PHOTO', 'PATIENT_DOCUMENT')", patientId);
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (IOException e) {
                // The row is gone inside the transaction; a stray file is logged for an operator, not a reason to keep personal data.
                log.error("Could not delete the stored file {} of erased patient {}", key, patientId, e);
            }
        }
    }
}
