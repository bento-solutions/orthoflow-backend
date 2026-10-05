package com.orthoflow.auth.application.service;

import com.orthoflow.auth.application.port.SessionRegistry;
import com.orthoflow.auth.domain.model.UserSession;
import com.orthoflow.auth.infrastructure.adapter.persistence.UserSessionJpaRepository;
import com.orthoflow.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionService implements SessionRegistry {

    /** Activity is recorded at most this often per session, so reading the schedule is not a write per request. */
    private static final long TOUCH_INTERVAL_SECONDS = 60;

    private final UserSessionJpaRepository sessions;

    @Transactional
    public UserSession open(UUID userId, String ip, String userAgent) {
        return sessions.save(UserSession.builder()
                .userId(userId)
                .ip(truncate(ip, 64))
                .userAgent(truncate(userAgent, 255))
                .build());
    }

    @Transactional(readOnly = true)
    public List<UserSession> active(UUID userId) {
        return sessions.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(userId);
    }

    @Transactional
    public void revoke(UUID userId, UUID sessionId) {
        UserSession session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Session not found"));
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(OffsetDateTime.now());
        }
    }

    @Transactional
    public void revokeOthers(UUID userId, UUID keepSessionId) {
        sessions.revokeOthers(userId, keepSessionId, OffsetDateTime.now());
    }

    @Transactional
    public void revokeAll(UUID userId) {
        sessions.revokeAll(userId, OffsetDateTime.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean isLive(UUID sessionId) {
        return sessions.findById(sessionId).map(s -> {
            if (s.getRevokedAt() != null) {
                return false;
            }
            OffsetDateTime now = OffsetDateTime.now();
            sessions.touch(sessionId, now, now.minusSeconds(TOUCH_INTERVAL_SECONDS));
            return true;
        }).orElse(false);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
