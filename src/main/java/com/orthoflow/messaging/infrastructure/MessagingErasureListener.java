package com.orthoflow.messaging.infrastructure;

import com.orthoflow.patient.application.port.PatientErasureListener;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * What the clinic sent a patient, and what they wrote back, is their data. The
 * outbox and event rows reference the patient with {@code ON DELETE SET NULL} so
 * that a log can outlive a deleted record — but a row kept with the phone number
 * and the text still identifies the person, so erasure removes them outright.
 */
@Component
@RequiredArgsConstructor
public class MessagingErasureListener implements PatientErasureListener {

    private final JdbcTemplate jdbc;

    @Override
    public void onPatientErased(UUID patientId) {
        jdbc.update("DELETE FROM message_events WHERE patient_id = ? OR outbox_id IN (SELECT id FROM message_outbox WHERE patient_id = ?)", patientId, patientId);
        jdbc.update("DELETE FROM message_outbox WHERE patient_id = ?", patientId);
    }
}
