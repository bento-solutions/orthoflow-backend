package com.orthoflow.messaging.application.port;

import java.util.Optional;

/**
 * A WhatsApp transport. Kept behind an interface so the provider can change
 * (the self-hosted Baileys bridge today) without touching the outbox.
 */
public interface WhatsAppProvider {

    boolean enabled();

    /** Sends {@code text} to {@code toDigits} (international, no plus), idempotent on {@code messageId}. */
    ChannelSender.Result send(String messageId, String toDigits, String text);

    /** Current link state of the sending device, when the provider can say. */
    Optional<String> sessionState();
}
