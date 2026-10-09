package com.orthoflow.patient.application.service;

import com.orthoflow.common.numbering.DocumentNumbers;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Patient codes: {@code P-00042}. Generated from a database sequence so two
 * receptionists registering at once never collide; staff may also type their
 * own to keep a numbering scheme from an older system, which is why uniqueness
 * is checked here as well as enforced by the table.
 */
@Component
@RequiredArgsConstructor
public class PatientCodeGenerator {

    private final JdbcTemplate jdbc;
    private final DocumentNumbers documentNumbers;

    public String next() {
        Long n = documentNumbers.next("patient_code");
        return "P-" + String.format("%05d", n);
    }

    /** Whether another patient (archived ones included) already has this code. */
    public boolean taken(UUID practiceId, String code, UUID exceptPatientId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM patients
                WHERE practice_id = ? AND lower(patient_code) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid)
                """, Integer.class, practiceId, code, exceptPatientId, exceptPatientId);
        return count != null && count > 0;
    }
}
