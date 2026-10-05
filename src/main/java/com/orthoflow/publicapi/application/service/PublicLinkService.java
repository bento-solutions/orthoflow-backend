package com.orthoflow.publicapi.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.publicapi.domain.model.PublicLink;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.publicapi.infrastructure.PublicLinkJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Tokens for the unauthenticated surface. A token is random, single-purpose and
 * (for per-patient links) expiring; only its SHA-256 is stored. Every way a
 * token can be wrong — unknown, expired, revoked, used up, wrong purpose —
 * answers the same "not found", so the endpoint cannot be used to learn which
 * tokens once existed.
 */
@Service
public class PublicLinkService {

    public record Issued(UUID id, String token) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PublicLinkJpaRepository links;
    private final byte[] secret;

    public PublicLinkService(PublicLinkJpaRepository links,
                             @Value("${orthoflow.public.link-secret:${app.jwt.secret}}") String secret) {
        this.links = links;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** A one-off link for one subject (a survey for one appointment, an invite for one patient). */
    @Transactional
    public Issued issue(UUID practiceId, PublicLinkPurpose purpose, String subjectType, UUID subjectId,
                        Duration ttl, Integer maxUses, UUID createdBy) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        PublicLink link = links.save(PublicLink.builder()
                .practiceId(practiceId).purpose(purpose).tokenHash(hash(token))
                .subjectType(subjectType).subjectId(subjectId)
                .expiresAt(ttl == null ? null : OffsetDateTime.now().plus(ttl))
                .maxUses(maxUses).createdBy(createdBy).build());
        return new Issued(link.getId(), token);
    }

    /**
     * The clinic-wide link for a purpose, created on first use. The token is
     * derived from a server secret and the rotation, so it can be shown again
     * whenever staff want to copy it.
     */
    @Transactional
    public String sharedToken(UUID practiceId, PublicLinkPurpose purpose) {
        PublicLink current = links.findByPracticeIdAndPurposeAndSubjectTypeAndRevokedAtIsNull(practiceId, purpose, PublicLink.SHARED)
                .stream().findFirst().orElseGet(() -> createShared(practiceId, purpose, 0, null));
        return derive(practiceId, purpose, current.getRotation());
    }

    /** Replaces the clinic-wide link: the old one stops working immediately. */
    @Transactional
    public String rotateShared(UUID practiceId, PublicLinkPurpose purpose, UUID actor) {
        int next = 0;
        for (PublicLink old : links.findByPracticeIdAndPurposeAndSubjectTypeAndRevokedAtIsNull(practiceId, purpose, PublicLink.SHARED)) {
            old.setRevokedAt(OffsetDateTime.now());
            next = Math.max(next, old.getRotation() + 1);
        }
        links.flush();
        createShared(practiceId, purpose, next, actor);
        return derive(practiceId, purpose, next);
    }

    /** The link a raw token stands for, or "not found" for any reason it cannot be used. Does not consume a use. */
    @Transactional(readOnly = true)
    public PublicLink resolve(String rawToken, PublicLinkPurpose purpose) {
        if (rawToken == null || rawToken.length() < 20 || rawToken.length() > 100) {
            throw invalid();
        }
        return links.findByTokenHash(hash(rawToken))
                .filter(l -> l.getPurpose() == purpose)
                .filter(l -> l.isUsable(OffsetDateTime.now()))
                .orElseThrow(PublicLinkService::invalid);
    }

    /** Takes one use of a capped link. False when it was used up in the meantime. */
    @Transactional
    public boolean consume(UUID linkId) {
        return links.consume(linkId) == 1;
    }

    @Transactional
    public void revoke(UUID practiceId, UUID id) {
        links.findById(id).filter(l -> l.getPracticeId().equals(practiceId)).ifPresent(l -> {
            if (l.getRevokedAt() == null) {
                l.setRevokedAt(OffsetDateTime.now());
            }
        });
    }

    private PublicLink createShared(UUID practiceId, PublicLinkPurpose purpose, int rotation, UUID actor) {
        return links.save(PublicLink.builder().practiceId(practiceId).purpose(purpose)
                .tokenHash(hash(derive(practiceId, purpose, rotation))).subjectType(PublicLink.SHARED)
                .rotation(rotation).createdBy(actor).build());
    }

    private String derive(UUID practiceId, PublicLinkPurpose purpose, int rotation) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(("shared|" + practiceId + "|" + purpose + "|" + rotation).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static NotFoundException invalid() {
        return new NotFoundException("This link is not valid");
    }
}
