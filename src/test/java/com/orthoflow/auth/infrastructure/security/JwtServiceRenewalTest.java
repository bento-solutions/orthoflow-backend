package com.orthoflow.auth.infrastructure.security;

import com.orthoflow.common.exception.UnauthorizedException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A session that outlasts one token, but not one clinic day.
 *
 * <p>Renewal exists so a token cannot expire under a dentist mid-consultation.
 * What these pin down is the other half: that renewing never extends the
 * sign-in itself, so a stolen token cannot be kept alive past the maximum.
 */
class JwtServiceRenewalTest {

    private static final String SECRET = "renewal-test-secret-key-0123456789abcdef0123456789";
    private static final UUID USER = UUID.randomUUID();
    private static final long TOKEN_MINUTES = 240;
    private static final long MAX_SESSION_MINUTES = 720;

    private MutableClock clock;
    private JwtService jwt;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-30T08:00:00Z"));
        jwt = new JwtService(SECRET, TOKEN_MINUTES, MAX_SESSION_MINUTES, clock);
    }

    private String signIn() {
        return jwt.generateToken(USER, "doc@clinic.test", "DOCTOR");
    }

    private String renew(String token) {
        return jwt.refreshToken(token, USER, "doc@clinic.test", "DOCTOR");
    }

    @Test
    void aTokenRecordsWhenThePersonSignedIn() {
        String token = signIn();

        assertThat(jwt.extractAuthTime(token)).isEqualTo(Instant.parse("2026-09-30T08:00:00Z"));
        assertThat(jwt.extractExpiration(token)).isEqualTo(Instant.parse("2026-09-30T12:00:00Z"));
    }

    @Test
    void renewingGivesAFullNewTokenButKeepsTheOriginalSignInTime() {
        String first = signIn();
        clock.advance(Duration.ofHours(3));

        String second = renew(first);

        assertThat(jwt.extractAuthTime(second)).isEqualTo(Instant.parse("2026-09-30T08:00:00Z"));
        assertThat(jwt.extractIssuedAt(second)).isEqualTo(Instant.parse("2026-09-30T11:00:00Z"));
        assertThat(jwt.extractExpiration(second)).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(jwt.extractUserId(second)).isEqualTo(USER);
        assertThat(jwt.extractRole(second)).isEqualTo("DOCTOR");
    }

    @Test
    void renewingAgainAndAgainNeverMovesTheSignInTime() {
        String token = signIn();
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofHours(3));
            token = renew(token);
        }

        assertThat(jwt.extractAuthTime(token)).isEqualTo(Instant.parse("2026-09-30T08:00:00Z"));
    }

    @Test
    void aRenewedTokenNeverOutlivesTheMaximumSession() {
        String token = signIn();
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofHours(3));
            token = renew(token);
        }
        // Renewed at 17:00, nine hours in: a full four hours would run to
        // 21:00, but the session ends twelve hours after sign-in, at 20:00.
        assertThat(jwt.extractExpiration(token)).isEqualTo(Instant.parse("2026-09-30T20:00:00Z"));
    }

    @Test
    void aSignInPastTheMaximumIsNotRenewedEvenIfItsTokenIsStillAlive() {
        // Issued before a maximum existed, with a lifetime longer than one now
        // allows: the token is live, the sign-in is not young enough to extend.
        String longLived = Jwts.builder()
                .subject(USER.toString())
                .claim("email", "doc@clinic.test")
                .claim("role", "DOCTOR")
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofHours(14))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                .compact();
        clock.advance(Duration.ofHours(12).plusMinutes(30));

        assertThat(jwt.isTokenValid(longLived)).isTrue();
        assertThatThrownBy(() -> renew(longLived))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("maximum length");
    }

    @Test
    void renewalIsOffWhenTheMaximumIsNotLongerThanOneToken() {
        JwtService off = new JwtService(SECRET, TOKEN_MINUTES, 0, clock);
        String token = off.generateToken(USER, "doc@clinic.test", "DOCTOR");

        assertThat(off.renewalEnabled()).isFalse();
        assertThatThrownBy(() -> off.refreshToken(token, USER, "doc@clinic.test", "DOCTOR"))
                .isInstanceOf(UnauthorizedException.class);
        // And an ordinary token is just as long as it always was.
        assertThat(off.extractExpiration(token)).isEqualTo(Instant.parse("2026-09-30T12:00:00Z"));
    }

    @Test
    void aTokenIssuedBeforeRenewalExistedIsRenewedFromItsIssueTime() {
        String legacy = Jwts.builder()
                .subject(USER.toString())
                .claim("email", "doc@clinic.test")
                .claim("role", "DOCTOR")
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofHours(4))))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                .compact();
        clock.advance(Duration.ofHours(3));

        String renewed = renew(legacy);

        assertThat(jwt.extractAuthTime(renewed)).isEqualTo(Instant.parse("2026-09-30T08:00:00Z"));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsNotRenewed() {
        JwtService elsewhere = new JwtService("another-secret-key-0123456789abcdef0123456789abcd", TOKEN_MINUTES,
                MAX_SESSION_MINUTES, clock);
        String foreign = elsewhere.generateToken(USER, "doc@clinic.test", "DOCTOR");

        assertThat(jwt.isTokenValid(foreign)).isFalse();
        assertThatThrownBy(() -> renew(foreign)).isInstanceOf(RuntimeException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
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
}
