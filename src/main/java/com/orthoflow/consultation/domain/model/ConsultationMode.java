package com.orthoflow.consultation.domain.model;

import java.util.Locale;

/**
 * When what is said in a consultation turns into commands.
 *
 * <ul>
 *   <li>{@link #DEFERRED} — the default. While recording nothing acts: the conversation is kept,
 *       timestamped, with its audio, and only the consultation's own phrases are answered (end it,
 *       pause, change the dictation language). When the doctor ends it, every sentence is replayed
 *       through the chart commands and the whole conversation is read by the model; everything found
 *       is put to the doctor to validate before it is saved.</li>
 *   <li>{@link #LIVE} — what every consultation did before: the chart commands act, and are read
 *       back, while the doctor speaks.</li>
 * </ul>
 */
public enum ConsultationMode {
    LIVE,
    DEFERRED;

    /** {@code live}/{@code deferred} in any case; anything else is {@code fallback}. */
    public static ConsultationMode parse(String value, ConsultationMode fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
