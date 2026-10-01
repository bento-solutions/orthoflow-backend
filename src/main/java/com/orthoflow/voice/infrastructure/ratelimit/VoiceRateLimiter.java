package com.orthoflow.voice.infrastructure.ratelimit;

import com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimitProperties.Limit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rolling-window throttle per signed-in user and voice route.
 *
 * <p>Counts requests in the last minute and the last hour, and how many are
 * running right now; a request over any of the three is refused with the
 * number of seconds until it would be let through. The window is a log of
 * timestamps (at most {@code perHour} of them, so a few KB per user), which
 * makes the answer exact at the window edge where a fixed-window counter
 * would let a burst of twice the limit through.
 *
 * <p>In memory and per instance, like {@code AuthRateLimitFilter}: enough for
 * the single container this runs in. Scaling out would move it to a shared
 * store or the proxy.
 */
@Component
public class VoiceRateLimiter {

    /** The routes that are throttled. */
    public enum Route {
        TRANSCRIBE("speech transcription"),
        INTERPRET("command interpretation"),
        SUMMARIZE("consultation summary"),
        EXTRACT("consultation reading");

        private final String label;

        Route(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Thrown by {@link #acquire}; carries the wait the client is told. */
    public static class Refused extends RuntimeException {
        private final long retryAfterSeconds;

        Refused(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** Held while a request runs; closing it frees its concurrency slot. */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Permit NOOP = () -> { };

    private final VoiceRateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public VoiceRateLimiter(VoiceRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    VoiceRateLimiter(VoiceRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Admits one request or throws {@link Refused}. Always close the permit,
     * in a {@code finally}, once the request has been answered.
     */
    public Permit acquire(Route route, UUID userId) {
        if (!properties.isEnabled()) {
            return NOOP;
        }
        Limit limit = limitFor(route);
        Bucket bucket = buckets.computeIfAbsent(route + "|" + userId, k -> new Bucket());
        long now = clock.millis();
        synchronized (bucket) {
            bucket.forget(now - HOUR.toMillis());

            if (limit.getConcurrent() > 0 && bucket.running >= limit.getConcurrent()) {
                throw new Refused("Too many " + route.label() + " requests are already running. "
                        + "Wait for them to finish.", 2);
            }
            long waitMinute = bucket.waitUntilBelow(limit.getPerMinute(), now, MINUTE.toMillis());
            long waitHour = bucket.waitUntilBelow(limit.getPerHour(), now, HOUR.toMillis());
            long wait = Math.max(waitMinute, waitHour);
            if (wait > 0) {
                throw new Refused("Too many " + route.label() + " requests. Try again in "
                        + wait + " seconds.", wait);
            }
            bucket.hits.addLast(now);
            bucket.running++;
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            // Once only: closing a permit twice must not free a slot that now
            // belongs to another request.
            if (released.compareAndSet(false, true)) {
                synchronized (bucket) {
                    bucket.running = Math.max(0, bucket.running - 1);
                }
            }
        };
    }

    private Limit limitFor(Route route) {
        return switch (route) {
            case TRANSCRIBE -> properties.getTranscribe();
            case INTERPRET -> properties.getInterpret();
            case SUMMARIZE -> properties.getSummarize();
            case EXTRACT -> properties.getExtract();
        };
    }

    private static final class Bucket {
        private final Deque<Long> hits = new ArrayDeque<>();
        private int running;

        /** Drops requests older than the longest window. */
        void forget(long olderThan) {
            while (!hits.isEmpty() && hits.peekFirst() <= olderThan) {
                hits.removeFirst();
            }
        }

        /**
         * Seconds until fewer than {@code limit} requests fall inside the
         * last {@code windowMillis}; 0 when one can be admitted now.
         */
        long waitUntilBelow(int limit, long now, long windowMillis) {
            if (limit <= 0) {
                return 0;
            }
            long inside = hits.stream().filter(t -> t > now - windowMillis).count();
            if (inside < limit) {
                return 0;
            }
            // The (inside - limit + 1)-th oldest request inside the window must
            // age out before there is room for one more.
            long[] stamps = hits.stream().filter(t -> t > now - windowMillis).mapToLong(Long::longValue).toArray();
            long frees = stamps[(int) (inside - limit)] + windowMillis;
            return Math.max(1, (frees - now + 999) / 1000);
        }
    }
}
