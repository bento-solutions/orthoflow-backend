package com.orthoflow.messaging.infrastructure.channel;

import com.orthoflow.messaging.application.port.ChannelSender;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.OutboxMessage;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class EmailChannelSender implements ChannelSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MessagingProperties properties;

    public EmailChannelSender(ObjectProvider<JavaMailSender> mailSender, MessagingProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public MessageChannel channel() {
        return MessageChannel.EMAIL;
    }

    @Override
    public boolean enabled() {
        return properties.getEmail().isEnabled() && mailSender.getIfAvailable() != null;
    }

    @Override
    public Result send(OutboxMessage message) {
        if (message.getRecipient() == null || message.getRecipient().isBlank()) {
            return Result.fail("No email address for this recipient");
        }
        if (message.getBody() == null) {
            return Result.fail("The message text is no longer available");
        }
        try {
            // Validated up front: an unparsable address will never succeed, so it must not be retried.
            new InternetAddress(message.getRecipient(), true);
            JavaMailSender sender = mailSender.getObject();
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(properties.getEmail().getFrom());
            helper.setTo(message.getRecipient());
            helper.setSubject(message.getSubject() == null ? "" : message.getSubject());
            helper.setText(message.getBody(), false);
            sender.send(mime);
            return Result.sent(null);
        } catch (jakarta.mail.internet.AddressException | MailParseException e) {
            return Result.fail("Invalid email address: " + e.getMessage());
        } catch (MailException e) {
            return Result.retry(e.getMostSpecificCause().getMessage(), 0);
        } catch (jakarta.mail.MessagingException e) {
            return Result.fail(e.getMessage());
        }
    }
}
