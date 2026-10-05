package com.orthoflow.messaging.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code orthoflow.messaging.*} — every channel is off until an operator turns it on. */
@Getter
@Setter
@Component
@ConfigurationProperties("orthoflow.messaging")
public class MessagingProperties {

    /** Message text is blanked this many days after creation; the delivery record stays. */
    private int retentionDays = 90;

    private Sender sender = new Sender();
    private Email email = new Email();
    private WhatsApp whatsapp = new WhatsApp();

    @Getter
    @Setter
    public static class Sender {
        private boolean enabled = true;
        private int batchSize = 20;
        private int maxAttempts = 6;
    }

    @Getter
    @Setter
    public static class Email {
        /** Needs spring.mail.host as well. */
        private boolean enabled = false;
        private String from = "no-reply@orthoflow.local";
    }

    /** The self-hosted Baileys bridge (the same service the CRM drives), one session per clinic. */
    @Getter
    @Setter
    public static class WhatsApp {
        private boolean enabled = false;
        private String baseUrl = "http://whatsapp-bot:3000";
        private String apiKey = "";
        /** The bridge session (a linked device) this clinic sends from. */
        private String sessionId = "orthoflow";
        /** HMAC key the bridge signs its webhook with (X-Bento-Signature). */
        private String webhookSecret = "";
        private String defaultCountryCode = "212";
        /** The clinic this bridge session belongs to (until a session-to-clinic mapping exists). */
        private java.util.UUID practiceId = com.orthoflow.common.tenancy.Practices.DEFAULT_ID;
        private int timeoutMs = 15000;
    }
}
