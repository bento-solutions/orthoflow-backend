package com.orthoflow.reminders.application.service;

import com.orthoflow.messaging.application.service.ConsentService;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.patient.application.port.PatientSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Which way to reach a patient: WhatsApp when they have a phone and have agreed to
 * it, otherwise email when they have an address and have agreed to that, otherwise
 * not at all. A patient who agreed to nothing is skipped silently rather than
 * queuing a message that would only be cancelled for want of consent.
 */
@Component
@RequiredArgsConstructor
public class ChannelPicker {

    private final ConsentService consent;

    public Optional<MessageChannel> pick(PatientSummary patient) {
        if (blank(patient.phone()) == false && consent.isOptedIn(patient.id(), MessageChannel.WHATSAPP)) {
            return Optional.of(MessageChannel.WHATSAPP);
        }
        if (blank(patient.email()) == false && consent.isOptedIn(patient.id(), MessageChannel.EMAIL)) {
            return Optional.of(MessageChannel.EMAIL);
        }
        return Optional.empty();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
