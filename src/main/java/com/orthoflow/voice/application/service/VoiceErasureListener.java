package com.orthoflow.voice.application.service;

import com.orthoflow.patient.application.port.PatientErasureListener;
import com.orthoflow.voice.domain.repository.VoiceCommandAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Erases what the voice trail holds about a patient when they are erased.
 *
 * <p>The clinical tables and the dictated sessions go with the patient through
 * the database cascade. The audit rows do not: {@code voice_command_audit}
 * keeps {@code patient_id} as {@code ON DELETE SET NULL} so the trail of who
 * dictated and confirmed what survives. But those rows also carry the
 * utterance, the resolved entities and the before/after values, which is the
 * patient's health information; left in place they would outlive the erasure
 * with the link to the person removed but the content intact.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceErasureListener implements PatientErasureListener {

    private final VoiceCommandAuditRepository audits;

    @Override
    public void onPatientErased(UUID patientId) {
        int scrubbed = audits.scrubPatientData(patientId);
        log.info("Patient erasure: blanked the content of {} voice audit row(s)", scrubbed);
    }
}
