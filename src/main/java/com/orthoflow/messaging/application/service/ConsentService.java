package com.orthoflow.messaging.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.messaging.domain.model.MessageChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-patient, per-channel opt-in (Law 09-08). Absence of a record means
 * "never asked", which is treated as not consented: nothing reaches a patient's
 * phone or inbox until someone has recorded that they agreed.
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public boolean isOptedIn(UUID patientId, MessageChannel channel) {
        Boolean optedIn = jdbc.query(
                "SELECT opted_in FROM patient_channel_consent WHERE patient_id = ? AND channel = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, patientId, channel.name());
        return Boolean.TRUE.equals(optedIn);
    }

    @Transactional(readOnly = true)
    public Map<MessageChannel, Boolean> forPatient(UUID patientId) {
        Map<MessageChannel, Boolean> result = new EnumMap<>(MessageChannel.class);
        jdbc.query("SELECT channel, opted_in FROM patient_channel_consent WHERE patient_id = ?",
                rs -> {
                    result.put(MessageChannel.valueOf(rs.getString(1)), rs.getBoolean(2));
                }, patientId);
        return result;
    }

    /**
     * Staff read and change consent through these: the patient must be one of the clinic's. The table
     * has no clinic of its own and is reached by native SQL, which the tenant filter does not cover,
     * so any signed-in user could read or switch another clinic's patient's consent by id (audit M3).
     */
    @Transactional(readOnly = true)
    public Map<MessageChannel, Boolean> forPatientOf(UUID practiceId, UUID patientId) {
        requirePatientOf(practiceId, patientId);
        return forPatient(patientId);
    }

    @Transactional
    public void recordFor(UUID practiceId, UUID patientId, MessageChannel channel, boolean optedIn, String source) {
        requirePatientOf(practiceId, patientId);
        record(patientId, channel, optedIn, source);
    }

    private void requirePatientOf(UUID practiceId, UUID patientId) {
        Boolean ours = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM patients WHERE id = ? AND practice_id = ? AND deleted_at IS NULL)",
                Boolean.class, patientId, practiceId);
        if (!Boolean.TRUE.equals(ours)) {
            throw new NotFoundException("Patient not found");
        }
    }

    @Transactional
    public void record(UUID patientId, MessageChannel channel, boolean optedIn, String source) {
        if (channel == MessageChannel.IN_APP) {
            return;
        }
        jdbc.update("""
                INSERT INTO patient_channel_consent (patient_id, channel, opted_in, source, updated_at)
                VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT (patient_id, channel) DO UPDATE
                SET opted_in = EXCLUDED.opted_in, source = EXCLUDED.source, updated_at = NOW()
                """, patientId, channel.name(), optedIn, source);
    }
}
