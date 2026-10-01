package com.orthoflow.voice.application.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.orthoflow.voice.domain.repository.VoiceCommandAuditRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoiceErasureListenerTest {

    @Test
    void blanksTheErasedPatientsDictatedContent() {
        VoiceCommandAuditRepository audits = mock(VoiceCommandAuditRepository.class);
        UUID patient = UUID.randomUUID();

        new VoiceErasureListener(audits).onPatientErased(patient);

        verify(audits).scrubPatientData(patient);
    }
}
