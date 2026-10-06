package com.orthoflow.staffmessages.application.service;

import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Staff-to-staff threads. A thread belongs to its participants: every read and
 * write checks membership, so a thread is "not found" to anyone else — an
 * administrator included. Kept apart from patient messaging because nothing here
 * leaves the building.
 */
@Service
@RequiredArgsConstructor
public class StaffMessageService {

    public record NewThread(@NotBlank @Size(max = 200) String subject, @NotEmpty List<UUID> recipientIds, @NotBlank @Size(max = 5000) String body) {
    }

    public record Reply(@NotBlank @Size(max = 5000) String body) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "StaffPerson")

    public record Person(UUID id, String name, String role) {
    }

    public record ThreadRow(UUID id, String subject, OffsetDateTime lastMessageAt, String lastSender, String preview, int unread,
                            List<String> participants) {
    }

    public record MessageRow(UUID id, UUID senderId, String senderName, String body, OffsetDateTime createdAt, boolean mine) {
    }

    public record ThreadDetail(UUID id, String subject, List<Person> participants, List<MessageRow> messages) {
    }

    private final JdbcTemplate jdbc;
    private final StaffNotifier notifier;
    private final LiveEventPublisher liveEvents;

    @Transactional(readOnly = true)
    public List<Person> recipients(UUID practiceId, UUID me) {
        return jdbc.query("SELECT id, first_name || ' ' || last_name AS name, role FROM users WHERE practice_id = ? AND active AND id <> ? ORDER BY first_name, last_name",
                (rs, i) -> new Person(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role")), practiceId, me);
    }

    @Transactional
    public ThreadDetail start(UUID practiceId, UUID me, NewThread r) {
        Set<UUID> recipients = new java.util.LinkedHashSet<>(r.recipientIds());
        recipients.remove(me);
        if (recipients.isEmpty()) {
            throw new ValidationException("Pick at least one other person");
        }
        for (UUID id : recipients) {
            Integer ok = jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ? AND practice_id = ? AND active", Integer.class, id, practiceId);
            if (ok == null || ok == 0) {
                throw new NotFoundException("Recipient not found");
            }
        }
        UUID threadId = UUID.randomUUID();
        jdbc.update("INSERT INTO staff_threads (id, practice_id, subject, created_by) VALUES (?, ?, ?, ?)", threadId, practiceId, r.subject().trim(), me);
        jdbc.update("INSERT INTO staff_thread_participants (thread_id, user_id, last_read_at) VALUES (?, ?, NOW())", threadId, me);
        for (UUID id : recipients) {
            jdbc.update("INSERT INTO staff_thread_participants (thread_id, user_id) VALUES (?, ?)", threadId, id);
        }
        post(practiceId, me, threadId, r.subject().trim(), r.body());
        return thread(practiceId, me, threadId);
    }

    @Transactional
    public ThreadDetail reply(UUID practiceId, UUID me, UUID threadId, Reply r) {
        requireMember(practiceId, me, threadId);
        String subject = jdbc.queryForObject("SELECT subject FROM staff_threads WHERE id = ?", String.class, threadId);
        post(practiceId, me, threadId, subject, r.body());
        return thread(practiceId, me, threadId);
    }

    @Transactional(readOnly = true)
    public List<ThreadRow> threads(UUID practiceId, UUID me) {
        return jdbc.query("""
                SELECT t.id, t.subject, t.last_message_at,
                       (SELECT u.first_name FROM staff_messages m JOIN users u ON u.id = m.sender_id WHERE m.thread_id = t.id ORDER BY m.created_at DESC LIMIT 1) AS last_sender,
                       (SELECT left(m.body, 120) FROM staff_messages m WHERE m.thread_id = t.id ORDER BY m.created_at DESC LIMIT 1) AS preview,
                       (SELECT count(*) FROM staff_messages m WHERE m.thread_id = t.id AND m.sender_id IS DISTINCT FROM p.user_id
                          AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)) AS unread,
                       (SELECT string_agg(u.first_name || ' ' || u.last_name, ', ' ORDER BY u.first_name) FROM staff_thread_participants x
                          JOIN users u ON u.id = x.user_id WHERE x.thread_id = t.id AND x.user_id <> p.user_id) AS others
                FROM staff_threads t JOIN staff_thread_participants p ON p.thread_id = t.id AND p.user_id = ?
                WHERE t.practice_id = ? ORDER BY t.last_message_at DESC LIMIT 100
                """, (rs, i) -> new ThreadRow(rs.getObject("id", UUID.class), rs.getString("subject"),
                rs.getObject("last_message_at", OffsetDateTime.class), rs.getString("last_sender"), rs.getString("preview"),
                rs.getInt("unread"), rs.getString("others") == null ? List.of() : List.of(rs.getString("others").split(", "))), me, practiceId);
    }

    /** Opening a thread marks it read for the person opening it. */
    @Transactional
    public ThreadDetail thread(UUID practiceId, UUID me, UUID threadId) {
        requireMember(practiceId, me, threadId);
        jdbc.update("UPDATE staff_thread_participants SET last_read_at = NOW() WHERE thread_id = ? AND user_id = ?", threadId, me);
        String subject = jdbc.queryForObject("SELECT subject FROM staff_threads WHERE id = ?", String.class, threadId);
        List<Person> people = jdbc.query("SELECT u.id, u.first_name || ' ' || u.last_name AS name, u.role FROM staff_thread_participants p JOIN users u ON u.id = p.user_id WHERE p.thread_id = ? ORDER BY u.first_name",
                (rs, i) -> new Person(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role")), threadId);
        List<MessageRow> messages = jdbc.query("""
                SELECT m.id, m.sender_id, COALESCE(u.first_name || ' ' || u.last_name, '—') AS name, m.body, m.created_at
                FROM staff_messages m LEFT JOIN users u ON u.id = m.sender_id WHERE m.thread_id = ? ORDER BY m.created_at
                """, (rs, i) -> new MessageRow(rs.getObject("id", UUID.class), rs.getObject("sender_id", UUID.class), rs.getString("name"),
                rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class), me.equals(rs.getObject("sender_id", UUID.class))), threadId);
        return new ThreadDetail(threadId, subject, people, messages);
    }

    @Transactional(readOnly = true)
    public int unread(UUID practiceId, UUID me) {
        Integer n = jdbc.queryForObject("""
                SELECT count(DISTINCT t.id) FROM staff_threads t JOIN staff_thread_participants p ON p.thread_id = t.id AND p.user_id = ?
                WHERE t.practice_id = ? AND EXISTS (SELECT 1 FROM staff_messages m WHERE m.thread_id = t.id AND m.sender_id IS DISTINCT FROM p.user_id
                  AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at))
                """, Integer.class, me, practiceId);
        return n == null ? 0 : n;
    }

    private void post(UUID practiceId, UUID me, UUID threadId, String subject, String body) {
        jdbc.update("INSERT INTO staff_messages (id, thread_id, sender_id, body) VALUES (?, ?, ?, ?)", UUID.randomUUID(), threadId, me, body.trim());
        jdbc.update("UPDATE staff_threads SET last_message_at = NOW() WHERE id = ?", threadId);
        String sender = jdbc.queryForObject("SELECT first_name || ' ' || last_name FROM users WHERE id = ?", String.class, me);
        String preview = body.trim().length() > 140 ? body.trim().substring(0, 140) + "…" : body.trim();
        for (UUID other : jdbc.queryForList("SELECT user_id FROM staff_thread_participants WHERE thread_id = ? AND user_id <> ?", UUID.class, threadId, me)) {
            notifier.toUser(practiceId, other, MessagePurpose.INTERNAL_MESSAGE, subject, sender + " : " + preview, "STAFF_THREAD", threadId);
        }
        liveEvents.publish(practiceId, "staff-message", threadId);
    }

    private void requireMember(UUID practiceId, UUID me, UUID threadId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM staff_thread_participants p JOIN staff_threads t ON t.id = p.thread_id WHERE p.thread_id = ? AND p.user_id = ? AND t.practice_id = ?",
                Integer.class, threadId, me, practiceId);
        if (n == null || n == 0) {
            throw new NotFoundException("Thread not found");
        }
    }
}
