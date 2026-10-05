package com.orthoflow.messaging.application.service;

import com.orthoflow.auth.application.port.AuthorityResolver;
import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.domain.repository.UserRepository;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Tells the staff who should know. A feature says "a cheque bounced, whoever
 * manages finance should see it"; this resolves who that is today — the active
 * users of the clinic whose role holds the permission — and drops an in-app
 * notification in each one's bell. Nobody is hard-coded, so changing what a role
 * may do changes who is told.
 */
@Service
@RequiredArgsConstructor
public class StaffNotifier {

    private final UserRepository users;
    private final AuthorityResolver authorityResolver;
    private final MessageService messages;

    @Transactional
    public int toPermission(UUID practiceId, Permission permission, MessagePurpose purpose, String subject, String body,
                            String relatedType, UUID relatedId) {
        int told = 0;
        for (var user : users.findAll()) {
            if (!user.isActive() || !practiceId.equals(user.getPracticeId())
                    || !authorityResolver.permissionsFor(practiceId, user.getRole()).contains(permission)) {
                continue;
            }
            messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(MessageChannel.IN_APP).purpose(purpose)
                    .recipientUserId(user.getId()).subject(subject).body(body).relatedType(relatedType).relatedId(relatedId)
                    .dedupeKey(relatedId == null ? null : purpose + ":" + relatedId + ":" + user.getId()).build());
            told++;
        }
        return told;
    }

    /** Everyone active in the clinic who holds this role. */
    @Transactional
    public int toRole(UUID practiceId, com.orthoflow.auth.domain.model.UserRole role, MessagePurpose purpose, String subject, String body,
                      String relatedType, UUID relatedId) {
        int told = 0;
        for (var user : users.findAll()) {
            if (user.isActive() && practiceId.equals(user.getPracticeId()) && user.getRole() == role) {
                messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(MessageChannel.IN_APP).purpose(purpose)
                        .recipientUserId(user.getId()).subject(subject).body(body).relatedType(relatedType).relatedId(relatedId).build());
                told++;
            }
        }
        return told;
    }

    @Transactional
    public void toUser(UUID practiceId, UUID userId, MessagePurpose purpose, String subject, String body, String relatedType, UUID relatedId) {
        messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(MessageChannel.IN_APP).purpose(purpose)
                .recipientUserId(userId).subject(subject).body(body).relatedType(relatedType).relatedId(relatedId).build());
    }
}
