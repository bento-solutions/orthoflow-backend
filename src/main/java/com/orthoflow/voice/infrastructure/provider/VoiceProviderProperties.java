package com.orthoflow.voice.infrastructure.provider;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
 */
@Component
@ConfigurationProperties(prefix = "orthoflow.voice.providers")
@Getter
@Setter
public class VoiceProviderProperties {

    private final Vendor gemini = new Vendor("https://generativelanguage.googleapis.com/v1beta");
    private final Vendor groq = new Vendor("https://api.groq.com/openai/v1");
    private final Vendor deepseek = new Vendor("https://api.deepseek.com");
    /**
     * AssemblyAI's synchronous endpoint, EU data-residency variant: a
     * consultation recording is health data, and the EU endpoint keeps it
     * inside the EU rather than routing to whichever region is nearest.
     */
    private final Vendor assemblyai = new Vendor("https://sync.eu.assemblyai.com/v1");

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

        public Vendor(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
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
