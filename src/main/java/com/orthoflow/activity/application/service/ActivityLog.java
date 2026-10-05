package com.orthoflow.activity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.activity.domain.model.ActivityEvent;
import com.orthoflow.activity.infrastructure.ActivityJpaRepository;
import com.orthoflow.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Written from the services, inside the transaction of the change it describes,
 * so the log and the data cannot disagree. Like the billing audit log, a failure
 * to serialise the diff never fails the business action it describes.
 */
@Service
@RequiredArgsConstructor
public class ActivityLog {

    private static final Logger log = LoggerFactory.getLogger(ActivityLog.class);

    private final ActivityJpaRepository events;
    private final ObjectMapper objectMapper;

    /** Records an event as the signed-in user. */
    @Transactional
    public void record(UUID practiceId, String entityType, UUID entityId, String action, Map<String, Object> diff) {
        UUID actorId = null;
        String actorName = null;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            actorId = user.id();
            actorName = user.email();
        }
        record(practiceId, actorId, actorName, entityType, entityId, action, diff);
    }

    @Transactional
    public void record(UUID practiceId, UUID actorId, String actorName, String entityType, UUID entityId,
                       String action, Map<String, Object> diff) {
        String json = null;
        if (diff != null && !diff.isEmpty()) {
            try {
                json = objectMapper.writeValueAsString(diff);
            } catch (Exception e) {
                log.warn("Could not serialise activity diff for {} {}", entityType, entityId, e);
            }
        }
        events.save(ActivityEvent.builder().practiceId(practiceId).actorId(actorId).actorName(actorName)
                .entityType(entityType).entityId(entityId).action(action).diff(json).build());
    }

    @Transactional(readOnly = true)
    public List<ActivityEvent> forEntity(UUID practiceId, String entityType, UUID entityId, int limit) {
        return events.findByPracticeIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                practiceId, entityType, entityId, PageRequest.of(0, Math.min(Math.max(limit, 1), 200)));
    }

    /** What changed between two snapshots, as {@code field: {from, to}}; only fields that differ appear. */
    public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : after.entrySet()) {
            Object old = before.get(e.getKey());
            if (!Objects.equals(old, e.getValue())) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("from", old == null ? null : old.toString());
                pair.put("to", e.getValue() == null ? null : e.getValue().toString());
                changes.put(e.getKey(), pair);
            }
        }
        return changes;
    }
}
