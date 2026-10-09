package com.orthoflow.messaging.application.service;

import com.orthoflow.common.tenancy.AnonymousRequestClinic;
import com.orthoflow.common.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The WhatsApp bridge reports on messages any clinic sent through it, so its
 * webhook works across clinics; {@link WhatsAppWebhookService} names the clinic of
 * everything it reads or writes. It is reached only after its signature is checked.
 */
@Component
public class WebhookRequestClinic implements AnonymousRequestClinic {

    @Override
    public Optional<UUID> clinicFor(HttpServletRequest request) {
        return request.getServletPath().startsWith("/webhooks/") ? Optional.of(TenantContext.ALL_CLINICS) : Optional.empty();
    }
}
