package com.orthoflow.voice.infrastructure.stt;

/**
 * Turns one captured audio clip into text.
 *
 * <p>The sibling of {@link com.orthoflow.voice.infrastructure.nlu.NluProvider}
 * for the other end of the pipeline, and pluggable for the same reason: which
 * service hears a consultation room is a deployment decision, not a code one.
 *
 * <p>Implementations never throw for an upstream problem — an unreachable or
 * misconfigured provider comes back as {@link TranscriptionResult#ofError},
 * so the browser falls back to its own recogniser instead of losing the
 * utterance.
 */
public interface TranscriptionProvider {

    /** Stable identifier, matched against {@code orthoflow.voice.stt.provider}. */
    String name();

    /** True when this provider is configured well enough to be called. */
    boolean isConfigured();

    /** The model this provider runs, for diagnostics when it answered nothing. */
    String model();

    /**
     * @param audio            raw bytes of the recorded clip
     * @param filename         original name; the extension is how some providers
     *                         infer the container
     * @param contentType      MIME type reported by the browser's MediaRecorder
     * @param languageOverride ISO-639-1 hint, or null/blank to auto-detect
     * @param prompt           optional bias text (names, clinical terms); may be null
     */
    TranscriptionResult transcribe(byte[] audio, String filename, String contentType,
                                   String languageOverride, String prompt);
}
