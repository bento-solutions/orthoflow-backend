package com.orthoflow.voice.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimiter.Permit;
import com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimiter.Refused;
import com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimiter.Route;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceRateLimiterTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T09:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final UUID doctor = UUID.randomUUID();
    private TestClock clock;
    private VoiceRateLimitProperties props;
    private VoiceRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        props = new VoiceRateLimitProperties();
        props.setTranscribe(new VoiceRateLimitProperties.Limit(3, 100, 0));
        limiter = new VoiceRateLimiter(props, clock);
    }

    /** One request that has already been answered. */
    private void answered(Route route, UUID user) {
        limiter.acquire(route, user).close();
    }

    @Test
    void refusesTheRequestOverThePerMinuteLimitAndSaysWhenToRetry() {
        answered(Route.TRANSCRIBE, doctor);
        clock.advance(Duration.ofSeconds(10));
        answered(Route.TRANSCRIBE, doctor);
        clock.advance(Duration.ofSeconds(10));
        answered(Route.TRANSCRIBE, doctor);
        clock.advance(Duration.ofSeconds(10));

        // 30 s in: the oldest of the three leaves the window at 60 s.
        assertThatThrownBy(() -> limiter.acquire(Route.TRANSCRIBE, doctor))
                .isInstanceOf(Refused.class)
                .satisfies(e -> assertThat(((Refused) e).retryAfterSeconds()).isEqualTo(30))
                .hasMessageContaining("speech transcription");
    }

    @Test
    void lettingTheOldestRequestAgeOutFreesExactlyOneSlot() {
        answered(Route.TRANSCRIBE, doctor);
        clock.advance(Duration.ofSeconds(30));
        answered(Route.TRANSCRIBE, doctor);
        answered(Route.TRANSCRIBE, doctor);

        clock.advance(Duration.ofSeconds(31)); // the first is now 61 s old
        assertThatCode(() -> answered(Route.TRANSCRIBE, doctor)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire(Route.TRANSCRIBE, doctor)).isInstanceOf(Refused.class);
    }

    @Test
    void aRefusedRequestDoesNotCountAgainstTheCaller() {
        for (int i = 0; i < 3; i++) answered(Route.TRANSCRIBE, doctor);
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> limiter.acquire(Route.TRANSCRIBE, doctor)).isInstanceOf(Refused.class);
        }

        clock.advance(Duration.ofSeconds(61));
        // Twenty refusals did not extend the penalty: the three real ones aged out.
        assertThatCode(() -> answered(Route.TRANSCRIBE, doctor)).doesNotThrowAnyException();
    }

    @Test
    void theHourlyCeilingHoldsEvenWhenTheMinuteOneNeverTrips() {
        props.setTranscribe(new VoiceRateLimitProperties.Limit(100, 5, 0));
        for (int i = 0; i < 5; i++) {
            answered(Route.TRANSCRIBE, doctor);
            clock.advance(Duration.ofMinutes(5));
        }
        // 25 minutes in, 5 requests in the last hour.
        assertThatThrownBy(() -> limiter.acquire(Route.TRANSCRIBE, doctor))
                .isInstanceOf(Refused.class)
                // the first one was made 25 minutes ago: 35 minutes to wait
                .satisfies(e -> assertThat(((Refused) e).retryAfterSeconds()).isEqualTo(35 * 60));

        clock.advance(Duration.ofMinutes(35));
        assertThatCode(() -> answered(Route.TRANSCRIBE, doctor)).doesNotThrowAnyException();
    }

    @Test
    void capsHowManyRunAtTheSameTimeAndFreesTheSlotWhenThePermitIsClosed() {
        props.setTranscribe(new VoiceRateLimitProperties.Limit(100, 1000, 2));
        Permit first = limiter.acquire(Route.TRANSCRIBE, doctor);
        limiter.acquire(Route.TRANSCRIBE, doctor);

        assertThatThrownBy(() -> limiter.acquire(Route.TRANSCRIBE, doctor))
                .isInstanceOf(Refused.class)
                .hasMessageContaining("already running");

        first.close();
        assertThatCode(() -> limiter.acquire(Route.TRANSCRIBE, doctor)).doesNotThrowAnyException();
    }

    @Test
    void closingAPermitTwiceDoesNotFreeAnotherRequestsSlot() {
        props.setTranscribe(new VoiceRateLimitProperties.Limit(100, 1000, 2));
        Permit first = limiter.acquire(Route.TRANSCRIBE, doctor);
        limiter.acquire(Route.TRANSCRIBE, doctor);

        first.close();
        first.close();
        limiter.acquire(Route.TRANSCRIBE, doctor); // running: 2 again

        assertThatThrownBy(() -> limiter.acquire(Route.TRANSCRIBE, doctor)).isInstanceOf(Refused.class);
    }

    @Test
    void oneDoctorsBurstDoesNotSlowAnotherOrAnotherRoute() {
        for (int i = 0; i < 3; i++) answered(Route.TRANSCRIBE, doctor);

        assertThatCode(() -> answered(Route.TRANSCRIBE, UUID.randomUUID())).doesNotThrowAnyException();
        assertThatCode(() -> answered(Route.INTERPRET, doctor)).doesNotThrowAnyException();
        assertThatCode(() -> answered(Route.SUMMARIZE, doctor)).doesNotThrowAnyException();
    }

    @Test
    void theDefaultsLeaveRoomForAHandsFreeExaminationButStopARunaway() {
        VoiceRateLimiter stock = new VoiceRateLimiter(new VoiceRateLimitProperties(), clock);
        UUID user = UUID.randomUUID();

        // Twelve clips a minute for an hour is a very busy examination.
        for (int minute = 0; minute < 60; minute++) {
            for (int clip = 0; clip < 12; clip++) {
                stock.acquire(Route.TRANSCRIBE, user).close();
            }
            clock.advance(Duration.ofMinutes(1));
        }
        assertThatCode(() -> stock.acquire(Route.TRANSCRIBE, user).close()).doesNotThrowAnyException();

        // A retry loop hammering the route is cut off within the minute.
        UUID runaway = UUID.randomUUID();
        int admitted = 0;
        for (int i = 0; i < 500; i++) {
            try {
                stock.acquire(Route.TRANSCRIBE, runaway).close();
                admitted++;
            } catch (Refused ignored) {
                // expected
            }
        }
        assertThat(admitted).isEqualTo(40);
    }

    @Test
    void canBeSwitchedOffForAnEnvironmentThatThrottlesAtTheProxy() {
        props.setEnabled(false);
        for (int i = 0; i < 50; i++) {
            assertThatCode(() -> limiter.acquire(Route.TRANSCRIBE, doctor)).doesNotThrowAnyException();
        }
    }
}
