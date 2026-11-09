package com.orthoflow.auth.infrastructure.adapter.notification;

import com.orthoflow.auth.application.port.PasswordResetNotifier;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends the reset link by e-mail, directly rather than through the messaging outbox: the outbox keeps
 * what it sends, and a working reset link is a password for thirty minutes.
 *
 * <p>The link used to be written to the server log, where anyone who could read the logs could take
 * over any account. With no mail transport configured it is not sent anywhere: an admin issues a link
 * from the user list instead ({@code AuthService#issueResetLink}). A developer machine without mail can
 * turn on {@code orthoflow.auth.log-reset-links}; nothing else should.
 */
@Component
public class MailPasswordResetNotifier implements PasswordResetNotifier {

    private static final Logger log = LoggerFactory.getLogger(MailPasswordResetNotifier.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final boolean emailEnabled;
    private final String from;
    private final boolean logLinks;

    public MailPasswordResetNotifier(ObjectProvider<JavaMailSender> mailSender,
                                     @Value("${orthoflow.messaging.email.enabled:false}") boolean emailEnabled,
                                     @Value("${orthoflow.messaging.email.from:no-reply@orthoflow.local}") String from,
                                     @Value("${orthoflow.auth.log-reset-links:false}") boolean logLinks) {
        this.mailSender = mailSender;
        this.emailEnabled = emailEnabled;
        this.from = from;
        this.logLinks = logLinks;
    }

    @Override
    public void sendResetLink(String toEmail, String resetUrl) {
        JavaMailSender sender = emailEnabled ? mailSender.getIfAvailable() : null;
        if (sender != null) {
            try {
                MimeMessage mime = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
                helper.setFrom(from);
                helper.setTo(toEmail);
                helper.setSubject("OrthoFlow : réinitialisation du mot de passe / password reset");
                helper.setText("""
                        Bonjour,

                        Une réinitialisation du mot de passe de votre compte OrthoFlow a été demandée.
                        Ce lien est valable 30 minutes et ne sert qu'une fois :

                        %s

                        Si vous n'êtes pas à l'origine de cette demande, ignorez ce message : votre mot de passe ne change pas.

                        —

                        A password reset was requested for your OrthoFlow account. The link above works once, for 30 minutes.
                        If you did not ask for it, ignore this message: your password stays the same.
                        """.formatted(resetUrl), false);
                sender.send(mime);
                log.info("Password reset link e-mailed to {}", masked(toEmail));
                return;
            } catch (MailException | MessagingException e) {
                // AuthService logs the failure and keeps the token; the link itself is never logged.
                throw new IllegalStateException("The password reset e-mail could not be sent: " + e.getMessage(), e);
            }
        }
        if (logLinks) {
            log.warn("orthoflow.auth.log-reset-links is on (development only). Reset link for {}: {}", toEmail, resetUrl);
            return;
        }
        log.warn("Password reset requested for {}, but no e-mail transport is configured: an admin can issue a link from the user list",
                masked(toEmail));
    }

    private static String masked(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at > 0 ? email.charAt(0) + "***" + email.substring(at) : "(no address)";
    }
}
