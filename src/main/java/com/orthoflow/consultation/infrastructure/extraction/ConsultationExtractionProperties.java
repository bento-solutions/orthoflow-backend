package com.orthoflow.consultation.infrastructure.extraction;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-consultation recording and the model that reads the conversation.
 *
 * <p>Off by default, like every stage of the voice pipeline that sends patient
 * content to an outside vendor. It is stricter than the others, because it
 * sends — and keeps — the whole conversation rather than a command: turning it
 * on needs a DPA with each vendor in the chain, a lawful basis, and a CNDP
 * notification under Law 09-08, and the doctor must tell the patient (the
 * start of every consultation asks them to attest that they did).
 *
 * <p>The chain is the same shape as the summary's: {@code provider}/{@code model}
 * first, then each {@code vendor:model} in {@code fallbacks}, skipping any
 * vendor with no key. Keys live once per vendor under
 * {@code orthoflow.voice.providers}. With no usable route the consultation still
 * records and still proposes what simple rules can find (phone, CIN, age,
 * allergies, insurer); the model is what finds the rest.
 */
@Component
@ConfigurationProperties(prefix = "orthoflow.consultation")
@Getter
@Setter
public class ConsultationExtractionProperties {

    /**
     * Whether the consultation feature exists at all. Off, every consultation
     * route answers 404-equivalent and the browser hides the button.
     */
    private boolean enabled = false;

    /**
     * The clinic's time zone. "Today" in the prompt, and what "in 15 days"
     * resolves against, must be the clinic's day — not the server's.
     */
    private String timezone = "Africa/Casablanca";

    /**
     * Whether the raw conversation is kept once the consultation is saved — as
     * a {@code CONSULTATION_TRANSCRIPT} note and on the consultation row.
     *
     * <p>Off by default and for testing only: production keeps no transcript
     * (docs/VOICE.md §9), and {@link ConsultationRetentionGuard} refuses to start
     * the prod profile with it on. Off, the transcript lives on the row only
     * while the consultation is open (so a crashed tab loses nothing) and is
     * erased when it is saved or discarded; what the doctor validated is kept.
     */
    private boolean retainTranscript = false;

    /**
     * An open consultation nothing has happened on for this long is discarded,
     * transcript and all, by {@code IdleConsultationCleanup}. Long enough to
     * survive a lunch break or a doctor finishing the review the next morning.
     */
    private int idleDiscardHours = 24;

    private final Extraction extraction = new Extraction();

    @Getter
    @Setter
    public static class Extraction {
        /** When false only the rule-based fallback runs and nothing is sent to a model. */
        private boolean enabled = false;

        private String provider = "groq";

        /**
         * Large on purpose, same reasoning as the summary: a wrong allergy is
         * worse than a slow one, and the smaller models drift from "only what
         * was said" on a long conversation.
         */
        private String model = "openai/gpt-oss-120b";

        private List<String> fallbacks = new ArrayList<>(List.of("groq:qwen/qwen3.8-27b", "deepseek:deepseek-flash"));

        private int timeoutMs = 25000;

        /**
         * What worked on both routes in measured runs. A reasoning model's
         * thinking counts against this and, near the limit, one primary stopped
         * early (valid JSON missing its last sections — the parser now treats
         * that as a failed route). Raising it helps that model, but a fallback on
         * a free tier caps output tokens per minute (qwen on Groq: 1000), so
         * raise it only with a plan that allows it.
         */
        private int maxOutputTokens = 2200;

        /**
         * The longest transcript sent to a model, in characters. A forty-minute
         * conversation is about 40 000. Past this the most recent part is sent.
         */
        private int maxTranscriptChars = 60000;

        /** The language of the labels the model writes, as a name it recognises. */
        private String language = "French";

        /** Treatments offered to the model for matching, so its answer can carry a catalog code. */
        private int maxCatalogEntries = 120;
    }
}
