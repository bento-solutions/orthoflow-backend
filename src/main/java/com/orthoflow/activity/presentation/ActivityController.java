package com.orthoflow.activity.presentation;

import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The activity panel (appointments now; other entities as they gain one). */
@RestController
@RequestMapping("/activity")
@RequiredArgsConstructor
public class ActivityController {

    @io.swagger.v3.oas.annotations.media.Schema(name = "ActivityEntry")
    public record Entry(UUID id, String actorName, String action, String diff, OffsetDateTime createdAt) {
    }

    /** Which permission reads which entity's history. An entity not listed here has no readable log. */
    private static final Map<String, String> READ_PERMISSION = Map.of(
            "APPOINTMENT", "AGENDA_VIEW",
            "PATIENT", "PATIENT_READ",
            "RECEIPT", "BILLING_READ",
            "EXPENSE", "FINANCE_VIEW",
            "LAB_ORDER", "LAB_ORDERS_MANAGE",
            "CHEQUE", "FINANCE_VIEW");

    private final ActivityLog activityLog;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<Entry> forEntity(@RequestParam String entityType, @RequestParam UUID entityId,
                                 @RequestParam(defaultValue = "50") int limit) {
        String permission = READ_PERMISSION.get(entityType);
        if (permission == null) {
            throw new ValidationException("No activity log for '" + entityType + "'");
        }
        currentUser.requireAuthority(permission);
        return activityLog.forEntity(currentUser.requirePracticeId(), entityType, entityId, limit).stream()
                .map(e -> new Entry(e.getId(), e.getActorName(), e.getAction(), e.getDiff(), e.getCreatedAt()))
                .toList();
    }
}
