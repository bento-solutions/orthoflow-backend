package com.orthoflow.messaging.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.messaging.application.dto.MessagingDtos.NotificationRow;
import com.orthoflow.messaging.application.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The notification bell. Always the signed-in user's own — there is no way to read someone else's. */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<NotificationRow> list(@RequestParam(defaultValue = "30") int limit) {
        return notifications.list(currentUser.requireUserId(), limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("count", notifications.unread(currentUser.requireUserId()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        notifications.markRead(currentUser.requireUserId(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        notifications.markAllRead(currentUser.requireUserId());
    }
}
