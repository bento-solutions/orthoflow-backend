package com.orthoflow.auth.infrastructure.security;

import com.orthoflow.common.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

/**
 * Issues and reads session tokens.
 *
 * <h2>A session can be renewed, but not indefinitely</h2>
 *
 * <p>A token lives {@code expiration-minutes}. A clinic day is longer than
 * that, and a token that expires mid-consultation logs the dentist out of the
 * chart they are dictating onto, with their hands in a patient's mouth. So a
 * still-valid token can be exchanged for a fresh one ({@link #refreshToken}) —
 * but every token carries the moment the person actually signed in
 * ({@code auth_time}), and nothing is issued that outlives
 * {@code auth_time + max-session-minutes}. The bound is what stops a stolen
 * token being renewed forever: past it the only way in is the password.
 *
 * <p>{@code max-session-minutes} at or below {@code expiration-minutes} turns
 * renewal off, which is also what an unset value means.
 */
@Component
public class JwtService {

    private static final String AUTH_TIME = "auth_time";
    private static final String SESSION_ID = "sid";

    private final SecretKey key;
    private final long expirationMillis;
    private final long maxSessionMillis;
    private final Clock clock;

    @Autowired
    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-minutes:480}") long expirationMinutes,
            @Value("${app.jwt.max-session-minutes:0}") long maxSessionMinutes) {
        this(secret, expirationMinutes, maxSessionMinutes, Clock.systemUTC());
    }

    JwtService(String secret, long expirationMinutes, long maxSessionMinutes, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirationMillis = expirationMinutes * 60_000;
        this.maxSessionMillis = maxSessionMinutes * 60_000;
        this.clock = clock;
    }

    public String generateToken(UUID userId, String email, String role) {
        return generateToken(userId, email, role, null);
    }

    /** A token tied to a tracked sign-in, so that sign-in can be listed and revoked on its own. */
    public String generateToken(UUID userId, String email, String role, UUID sessionId) {
        Instant now = clock.instant();
        return build(userId, email, role, sessionId, now, now);
    }

    /** Whether a valid token can be exchanged for a later one at all. */
    public boolean renewalEnabled() {
        return maxSessionMillis > expirationMillis;
    }

    /**
     * A fresh token for the same sign-in. The caller has already established
     * that {@code presented} is a live token for an active account (the
     * request came through {@link JwtAuthFilter}); this decides only whether
     * the sign-in is still young enough to extend, and how far.
     */
    public String refreshToken(String presented, UUID userId, String email, String role) {
        if (!renewalEnabled()) {
            throw new UnauthorizedException("This session cannot be renewed. Sign in again.");
        }
        Instant now = clock.instant();
        Instant authTime = extractAuthTime(presented);
        if (!now.isBefore(authTime.plusMillis(maxSessionMillis))) {
            throw new UnauthorizedException("This session has reached its maximum length. Sign in again.");
        }
        return build(userId, email, role, extractSessionId(presented).orElse(null), authTime, now);
    }

    private String build(UUID userId, String email, String role, UUID sessionId, Instant authTime, Instant now) {
        Instant expiry = now.plusMillis(expirationMillis);
        Instant ceiling = authTime.plusMillis(Math.max(maxSessionMillis, expirationMillis));
        if (expiry.isAfter(ceiling)) {
            expiry = ceiling;
        }
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .claim(AUTH_TIME, authTime.getEpochSecond())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry));
        if (sessionId != null) {
            builder.claim(SESSION_ID, sessionId.toString());
        }
        return builder.signWith(key).compact();
    }

    /**
     * When the person signed in. A token minted before renewal existed has no
     * such claim; its own issue time is the sign-in.
     */
    public Instant extractAuthTime(String token) {
        Object claim = extractAllClaims(token).get(AUTH_TIME);
        return claim instanceof Number seconds ? Instant.ofEpochSecond(seconds.longValue()) : extractIssuedAt(token);
    }

    /** The tracked sign-in this token belongs to; empty for a token issued before sessions were tracked. */
    public java.util.Optional<UUID> extractSessionId(String token) {
        String sid = extractClaim(token, claims -> claims.get(SESSION_ID, String.class));
        return sid == null ? java.util.Optional.empty() : java.util.Optional.of(UUID.fromString(sid));
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(extractClaim(token, Claims::getSubject));
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    /** When this token stops being accepted. */
    public Instant extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration).toInstant();
    }

    /** When this token was issued — checked against User#sessionsValidAfter. */
    public Instant extractIssuedAt(String token) {
        Date issuedAt = extractClaim(token, Claims::getIssuedAt);
        return issuedAt == null ? Instant.EPOCH : issuedAt.toInstant();
    }

    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                // Expiry is judged by the clock that issued the token.
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
