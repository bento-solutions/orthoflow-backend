package com.orthoflow.voice.infrastructure.stt;

/**
 * What the provider made of one audio clip.
 *
 * <p>A blank {@code text} with a non-null {@code error} is an expected result,
 * not an exception: the provider was unreachable, or misconfigured, or
 * declined the clip. The client falls back to browser-side recognition in
 * that case rather than failing the utterance outright, mirroring how the
 * NLU fallback treats an unavailable provider.
 *
 * @param normalized the transcript respelled into the command vocabulary, or
 *                   null when the provider does not produce one
 * @param model      the model that actually produced it, when a provider can
 *                   fall back between models; null means the configured one
 */
public record TranscriptionResult(
        String text,
        String normalized,
        String language,
        Double durationSeconds,
        String model,
        String error
) {
    public static TranscriptionResult ofText(String text, String language, Double durationSeconds) {
        return ofText(text, null, language, durationSeconds, null);
    }

    public static TranscriptionResult ofText(String text, String normalized, String language,
                                             Double durationSeconds, String model) {
        return new TranscriptionResult(
                text == null ? "" : text.trim(),
                normalized == null || normalized.isBlank() ? null : normalized.trim(),
                language, durationSeconds, model, null);
    }

    public static TranscriptionResult ofError(String error) {
        return new TranscriptionResult("", null, null, null, null, error);
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
