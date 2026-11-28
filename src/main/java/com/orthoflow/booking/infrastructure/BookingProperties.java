package com.orthoflow.booking.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for public patient booking, emergency click-to-chat, and CAPTCHA verification.
 */
@Getter
@Setter
@Component
@ConfigurationProperties("orthoflow.booking")
public class BookingProperties {

    /**
     * Doctor account whose calendar drives public availability and direct bookings
     * on the /book surface.
     */
    private String doctorEmail = "achraf.ouajid@bentosolutions.com";

    /** When true, public booking creates a confirmed appointment directly instead of a pending request. */
    private boolean directBook = true;

    /** Default slot duration in minutes when no appointment type is specified on availability lookup. */
    private int defaultDurationMinutes = 30;

    /** Existing Baileys WhatsApp number in international format (e.g. +212754411515). Never hardcoded. */
    private String baileysPhoneNumber = "";

    /** Marker prefixed in emergency WhatsApp links so inbound routing flags urgent cases immediately. */
    private String emergencyMarker = "URGENT";

    /** Optional server-to-server API key for trusted booking proxies (bypasses browser CAPTCHA when matched). */
    private String apiKey = "";

    private boolean captchaEnabled = false;
    private String captchaSiteKey = "";
    private String captchaSecretKey = "";
    private String captchaVerifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
}
