package com.orthoflow.messaging.application.service;

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
