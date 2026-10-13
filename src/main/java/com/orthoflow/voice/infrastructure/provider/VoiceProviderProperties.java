package com.orthoflow.voice.infrastructure.provider;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One set of credentials per vendor, shared by every stage of the voice
 * pipeline that talks to it.
 *
 * <p>Transcription, interpretation and the consultation summary each fall
 * back from one vendor to another, so the same Groq key serves Whisper as the
 * last transcription fallback, a fast model as the interpretation fallback
 * and the summariser. Holding a key per vendor here — rather than per stage,
 * as {@code orthoflow.voice.stt.api-key} used to — is what lets a chain name a
 * vendor without also needing its own copy of the key.
 *
 * <p>The per-stage keys still work: a blank vendor key falls back to the
 * stage key when that stage's primary provider is the same vendor, so an
 * existing deployment keeps running unchanged. Every key is read here, on the
 * server, and none of them ever reaches the browser.
 *
 * <p>A vendor is only <em>usable</em> when it may lawfully receive patient data.
 * With {@link #requireDataProtection} on (the default; the dev profile turns it
 * off) a vendor that the operator has not attested as {@code data-protected}, or
 * whose attested {@code region} is outside {@link #allowedRegions} when that list
 * is set, is skipped by every stage: its key is treated as absent, whichever
 * fallback chain names it. Skipping is loud at startup ({@link VoiceDataHandlingReport}).
 */
@Component
@ConfigurationProperties(prefix = "orthoflow.voice.providers")
@Getter
@Setter
public class VoiceProviderProperties {

    /**
     * Patient audio and text only go to a vendor attested as data-protected (and in an
     * allowed region). Off only where there is no patient data, such as local development.
     */
    private boolean requireDataProtection = true;
    /**
     * Regions patient data may be processed in, as attested per vendor ({@code region}), for
     * example {@code EU,MA}. Empty: no restriction by region. A vendor with no declared
     * region is not allowed once this is set.
     */
    private java.util.List<String> allowedRegions = new java.util.ArrayList<>();

    private final Vendor gemini = new Vendor("https://generativelanguage.googleapis.com/v1beta", this);
    private final Vendor groq = new Vendor("https://api.groq.com/openai/v1", this);
    private final Vendor deepseek = new Vendor("https://api.deepseek.com", this);
    /**
     * AssemblyAI's synchronous endpoint, EU data-residency variant: a
     * consultation recording is health data, and the EU endpoint keeps it
     * inside the EU rather than routing to whichever region is nearest.
     */
    private final Vendor assemblyai = new Vendor("https://sync.eu.assemblyai.com/v1", this);

    @Getter
    @Setter
    public static class Vendor {
        private String apiKey = "";
        /** No trailing slash. */
        private String baseUrl;
        /**
         * The operator's attestation that this key is on a plan whose terms
         * keep submitted audio and text out of the vendor's own model
         * training and product improvement, and that a data-processing
         * agreement is in place. The code cannot verify that — it is a
         * statement about a contract — so it defaults to false and a vendor
         * without it is named in a startup warning whenever patient audio or
         * text is routed to it. A free-tier key is the usual case where it
         * is not true.
         */
        private boolean dataProtected = false;
        /** Where this vendor processes what it is sent (an operator attestation), for example EU, US, CN. */
        private String region = "";

        @lombok.Getter(lombok.AccessLevel.NONE)
        @lombok.Setter(lombok.AccessLevel.NONE)
        private final VoiceProviderProperties owner;

        public Vendor(String baseUrl, VoiceProviderProperties owner) {
            this.baseUrl = baseUrl;
            this.owner = owner;
        }

        /** A key is configured, whether or not the vendor may be used. */
        public boolean hasConfiguredKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** The vendor must not receive patient data: not attested, or processed outside the allowed regions. */
        public boolean isBlocked() {
            if (!owner.requireDataProtection) {
                return false;
            }
            if (!dataProtected) {
                return true;
            }
            List<String> allowed = owner.allowedRegions;
            return !allowed.isEmpty() && allowed.stream().noneMatch(a -> a.trim().equalsIgnoreCase(region == null ? "" : region.trim()));
        }

        /** A key is configured and the vendor may be used. */
        public boolean hasKey() {
            return hasConfiguredKey() && !isBlocked();
        }

        public String base() {
            return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        }
    }

    /**
     * The key for {@code vendor}, or {@code stageKey} when the vendor has none
     * of its own and is the stage's configured primary — the backward
     * compatible path for a deployment that only ever set the stage key.
     */
    public String keyFor(String vendor, String stagePrimary, String stageKey) {
        Vendor v = vendor(vendor);
        if (v != null && v.isBlocked()) {
            // Not the stage key either: the legacy key path must not route around the attestation.
            return "";
        }
        if (v != null && v.hasKey()) {
            return v.getApiKey().trim();
        }
        if (vendor.equalsIgnoreCase(stagePrimary) && stageKey != null && !stageKey.isBlank()) {
            return stageKey.trim();
        }
        return "";
    }

    public Vendor vendor(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase()) {
            case "gemini" -> gemini;
            case "groq" -> groq;
            case "deepseek" -> deepseek;
            case "assemblyai" -> assemblyai;
            default -> null;
        };
    }
}
