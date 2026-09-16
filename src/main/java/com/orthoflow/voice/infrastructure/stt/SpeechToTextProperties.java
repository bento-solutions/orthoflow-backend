package com.orthoflow.voice.infrastructure.stt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Server-side speech-to-text for the voice-first clinical workflow.
 *
 * <p>This is the sibling of {@link
 * com.orthoflow.voice.infrastructure.nlu.VoiceNluProperties} for the other end
 * of the pipeline: turning captured microphone audio into a transcript. The
 * browser's own {@code SpeechRecognition} still works and is still the default
 * on the client; this exists so a deployment can send audio to a hosted
 * Whisper endpoint instead of whatever cloud service the browser happens to
 * use (Chrome and Edge upload to Google; Safari is on-device).
 *
 * <p>{@code enabled: false} is the deliberate default. Turning it on means
 * consultation-room audio — which carries health information about the patient
 * and anyone else in the room — is sent to a third-party processor, so it
 * needs the same data-protection groundwork the NLU fallback does (DPA,
 * lawful basis, CNDP notification under Law 09-08). The API key is held here
 * on the server and never reaches the browser.
 *
 * <p>Two providers ship. {@code groq} targets Groq's OpenAI-compatible audio
 * API, and any endpoint implementing {@code POST /audio/transcriptions} with
 * the same multipart shape (a self-hosted {@code faster-whisper} server, for
 * example) works by pointing {@code base-url} at it. {@code gemini} targets
 * Google's Interactions API, which is not multipart and not OpenAI-shaped, so
 * it carries its own {@code gemini-base-url} and {@code gemini-model} rather
 * than overloading the two above — a deployment can switch {@code provider}
 * back and forth without rewriting either provider's endpoint settings.
 */
@Component
@ConfigurationProperties(prefix = "orthoflow.voice.stt")
@Getter
@Setter
public class SpeechToTextProperties {

    /**
     * Whether {@code POST /voice/transcribe} will call the provider at all.
     * When false the endpoint answers 200 with an empty transcript and
     * {@code error: "stt-disabled"} so the client can fall back to
     * browser-side recognition without treating it as a failure.
     */
    private boolean enabled = false;

    /** {@code groq} or {@code gemini}; anything else disables transcription. */
    private String provider = "groq";

    /** Held server-side only. Supplied via {@code VOICE_STT_API_KEY}. */
    private String apiKey = "";

    /**
     * Whisper variant. {@code whisper-large-v3-turbo} is fastest and cheapest
     * and is the right default for one-utterance clinical dictation;
     * {@code whisper-large-v3} is marginally more accurate on heavy accents.
     */
    private String model = "whisper-large-v3-turbo";

    /** OpenAI-compatible audio API root, no trailing slash. */
    private String baseUrl = "https://api.groq.com/openai/v1";

    /**
     * Optional ISO-639-1 hint (e.g. {@code fr}, {@code ar}, {@code en}). Left
     * blank, Whisper detects the language per utterance, which is the right
     * behaviour for Moroccan clinicians who code-switch mid-sentence.
     */
    private String language = "";

    /**
     * 0 keeps the decode deterministic — the correct choice for a clinical
     * record, where a reproducible transcript matters more than a fluent one.
     */
    private double temperature = 0;

    // ── Gemini ──────────────────────────────────────────────────────────

    /**
     * Gemini's Interactions API root, no trailing slash. The client appends
     * {@code /interactions}.
     */
    private String geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta";

    /**
     * Gemini is a general multimodal model rather than a dedicated ASR model,
     * which is the point: it transcribes French-with-Darija code-switching and
     * clinical vocabulary noticeably better than Whisper does, because it can
     * use the prompt as context rather than decoding phonemes in isolation.
     * The cost is that it can be talked out of transcribing — see the system
     * instruction in {@code GeminiTranscriptionClient}.
     */
    private String geminiModel = "gemini-3.8-flash";

    /**
     * Tried when the primary answers 429 or 5xx, or times out. The newest
     * Flash model is regularly "experiencing high demand", and a dentist
     * mid-examination cannot wait it out. Blank disables the fallback.
     */
    private String geminiFallbackModel = "gemini-3.5-flash";

    /**
     * How hard the model may think before answering. Transcription is not a
     * reasoning task and thinking only adds latency to a call that sits in
     * front of a doctor mid-examination. {@code low} rather than
     * {@code minimal}: gemini-3.8-flash rejects {@code minimal} outright.
     * Blank omits the field and takes the model's default.
     */
    private String geminiThinkingLevel = "low";

    /**
     * Thinking level used when the fallback model answers. {@code minimal} by
     * default: the fallback runs when the primary is overloaded, which is
     * exactly when latency matters most, and gemini-3.5-flash accepts it
     * (and answers faster with it) where gemini-3.8-flash does not.
     */
    private String geminiFallbackThinkingLevel = "minimal";

    private int timeoutMs = 15000;

    /**
     * Hard ceiling on an uploaded clip, enforced before the provider call so
     * an oversized body is a fast, clear 400 rather than a slow upstream
     * rejection. 15 MB is far above any legitimate push-to-talk or
     * examination-segment recording.
     */
    private long maxAudioBytes = 15L * 1024 * 1024;

    /**
     * The model name to report back to the client for whichever provider is
     * active, so the browser's diagnostics name what actually ran rather than
     * whatever the other provider's setting happens to hold.
     */
    public String activeModel() {
        return "gemini".equalsIgnoreCase(provider) ? geminiModel : model;
    }
}
