package com.orthoflow.messaging.infrastructure.channel;

import com.orthoflow.messaging.application.port.ChannelSender;
import com.orthoflow.messaging.application.port.WhatsAppProvider;
import com.orthoflow.messaging.application.service.PhoneNumbers;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.OutboxMessage;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WhatsAppChannelSender implements ChannelSender {

    private final WhatsAppProvider provider;
    private final MessagingProperties properties;

    @Override
    public MessageChannel channel() {
        return MessageChannel.WHATSAPP;
    }

    @Override
    public boolean enabled() {
        return provider.enabled();
    }

    @Override
    public Result send(OutboxMessage message) {
        String digits = PhoneNumbers.toInternationalDigits(message.getRecipient(), properties.getWhatsapp().getDefaultCountryCode());
        if (digits == null) {
            return Result.fail("Not a usable phone number: " + message.getRecipient());
        }
        if (message.getBody() == null) {
            return Result.fail("The message text is no longer available");
        }
        // The bridge accepts 8-64 alphanumerics as an id and de-duplicates on it.
        return provider.send(message.getId().toString().replace("-", ""), digits, message.getBody());
    }
}
