package com.orthoflow.messaging.application.service;

import com.orthoflow.messaging.application.dto.MessagingDtos.NotificationRow;
import com.orthoflow.messaging.domain.model.OutboxMessage;
import com.orthoflow.messaging.infrastructure.persistence.OutboxJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The bell: a staff member's in-app messages. They share the outbox, so they get retention and the log for free. */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final OutboxJpaRepository outbox;

    @Transactional(readOnly = true)
    public List<NotificationRow> list(UUID userId, int limit) {
        return outbox.notificationsFor(userId, PageRequest.of(0, Math.min(Math.max(limit, 1), 100))).stream()
                .map(NotificationRow::from).toList();
    }

    @Transactional(readOnly = true)
    public long unread(UUID userId) {
        return outbox.unreadCount(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        outbox.findById(id).filter(m -> userId.equals(m.getRecipientUserId())).ifPresent((OutboxMessage m) -> {
            if (m.getReadAt() == null) {
                m.setReadAt(OffsetDateTime.now());
            }
        });
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return outbox.markAllRead(userId, OffsetDateTime.now());
    }
}
