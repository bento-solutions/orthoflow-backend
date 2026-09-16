package com.orthoflow.voice.infrastructure.nlu;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the natural-language fallback and for how much of an
 * utterance is retained.
 *
 * <p>The default is {@code provider: disabled}, and that is a deliberate
 * choice rather than an unfinished one. The deterministic grammar in the
 * browser handles structured clinical dictation without any audio or text
 * leaving the machine; switching a provider on means transcripts of a
 * consultation — which contain health information, and not only the speaking
 * doctor's — start being sent to a third party. That is a data-protection
 * decision for whoever deploys this (DPA, lawful basis, CNDP notification
 * under Law 09-08), not a default a codebase should make on their behalf.
 *
 * <p>{@code openai-compatible} exists so that decision can be answered with a
 * model running on the clinic's own VPS (Ollama, vLLM, LM Studio), which keeps
 * the disclosure question inside the same trust boundary as the database.
 */
@Component
@ConfigurationProperties(prefix = "orthoflow.voice")
@Getter
@Setter
public class VoiceNluProperties {

    private final Nlu nlu = new Nlu();
    private final Audit audit = new Audit();

    @Getter
    @Setter
    public static class Nlu {
        /** disabled | anthropic | openai-compatible | gemini */
        private String provider = "disabled";
        private String model = "claude-haiku-4-5-20251001";
        private String apiKey = "";
        /** Required for openai-compatible, e.g. http://localhost:11434/v1 for Ollama. */
        private String baseUrl = "";
        private int timeoutMs = 9000;
        /** Utterances longer than this are refused rather than truncated mid-clause. */
        private int maxTranscriptChars = 800;
        private int maxOutputTokens = 700;

        // ── Gemini-specific NLU settings ────────────────────────────────
        /** Gemini model for NLU. Flash is fast and cheap for intent classification. */
        private String geminiModel = "gemini-3.5-flash";
        /** Falls back to the STT api-key when blank, so one Gemini key drives both. */
        private String geminiApiKey = "";
        /** Gemini Interactions API root. */
        private String geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta";
        /**
         * Thinking level. Low rather than minimal: the newer Flash models reject
         * minimal, and a rejected request is a slower failure than a little
         * thinking. Blank omits the field.
         */
        private String geminiThinkingLevel = "low";
    }

    @Getter
    @Setter
    public static class Audit {
        /**
         * Whether the raw utterance is kept on the audit row. A pilot needs it
         * to measure recognition accuracy; a production clinic generally should
         * not keep speech transcripts beside patient identifiers (audit XII.5).
         * The interpreted command is always retained either way.
         */
        private boolean storeTranscript = true;
    }
}
