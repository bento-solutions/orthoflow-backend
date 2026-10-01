package com.orthoflow.voice.infrastructure.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-user ceilings on the three voice routes that spend money or hold a
 * request thread: transcription (a vendor call per clip, up to 17 s of a
 * thread), interpretation and the end-of-consultation summary (up to three
 * 30 s vendor calls). Without them a stolen 4-hour token, or a client stuck in
 * a retry loop, is an open tap on the clinic's paid quota.
 *
 * <p>The defaults leave generous headroom over real use: hands-free dictation
 * produces at most a dozen clips a minute, and a summary is asked for once per
 * consultation. They are set to stop a runaway, not to ration a busy doctor.
 */
@Component
@ConfigurationProperties(prefix = "orthoflow.voice.rate-limit")
@Getter
@Setter
public class VoiceRateLimitProperties {

    /** Off switch for an environment that throttles at the proxy instead. */
    private boolean enabled = true;

    private Limit transcribe = new Limit(40, 1200, 6);
    private Limit interpret = new Limit(30, 600, 3);
    private Limit summarize = new Limit(6, 60, 1);
    /**
     * Reading a consultation as it goes: the browser asks every ten seconds or
     * so while the conversation grows, one at a time. 12 a minute is double
     * that; 400 an hour covers a long consultation with room to spare.
     */
    private Limit extract = new Limit(12, 400, 1);

    @Getter
    @Setter
    public static class Limit {
        /** Requests in any rolling 60 seconds. */
        private int perMinute;
        /** Requests in any rolling 60 minutes. */
        private int perHour;
        /** Requests being served at the same moment. */
        private int concurrent;

        public Limit() {
        }

        public Limit(int perMinute, int perHour, int concurrent) {
            this.perMinute = perMinute;
            this.perHour = perHour;
            this.concurrent = concurrent;
        }
    }
}
