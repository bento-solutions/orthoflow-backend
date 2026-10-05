package com.orthoflow.messaging.infrastructure.persistence;

import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessageStatus;
import com.orthoflow.messaging.domain.model.OutboxMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxJpaRepository extends JpaRepository<OutboxMessage, UUID> {

    boolean existsByDedupeKey(String dedupeKey);

    Optional<OutboxMessage> findByDedupeKey(String dedupeKey);

    Optional<OutboxMessage> findByIdAndPracticeId(UUID id, UUID practiceId);

    Optional<OutboxMessage> findByProviderMessageId(String providerMessageId);

    @Query("""
            SELECT m FROM OutboxMessage m
            WHERE m.practiceId = :practiceId AND m.channel <> com.orthoflow.messaging.domain.model.MessageChannel.IN_APP
              AND (:channel IS NULL OR m.channel = :channel)
              AND (:status IS NULL OR m.status = :status)
              AND (:patientId IS NULL OR m.patientId = :patientId)
            ORDER BY m.createdAt DESC
            """)
    Page<OutboxMessage> search(@Param("practiceId") UUID practiceId, @Param("channel") MessageChannel channel,
                               @Param("status") MessageStatus status, @Param("patientId") UUID patientId,
                               Pageable pageable);

    @Query("""
            SELECT m FROM OutboxMessage m
            WHERE m.channel = com.orthoflow.messaging.domain.model.MessageChannel.IN_APP
              AND m.recipientUserId = :userId
            ORDER BY m.createdAt DESC
            """)
    List<OutboxMessage> notificationsFor(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT COUNT(m) FROM OutboxMessage m
            WHERE m.channel = com.orthoflow.messaging.domain.model.MessageChannel.IN_APP
              AND m.recipientUserId = :userId AND m.readAt IS NULL
            """)
    long unreadCount(@Param("userId") UUID userId);

    @Modifying
    @Query("""
            UPDATE OutboxMessage m SET m.readAt = :now
            WHERE m.channel = com.orthoflow.messaging.domain.model.MessageChannel.IN_APP
              AND m.recipientUserId = :userId AND m.readAt IS NULL
            """)
    int markAllRead(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /** The retention job: blank the text of old, finished messages and keep the delivery facts. */
    @Modifying
    @Query("""
            UPDATE OutboxMessage m SET m.body = NULL, m.subject = NULL, m.bodyPurgedAt = :now
            WHERE m.bodyPurgedAt IS NULL AND m.createdAt < :before
              AND m.status NOT IN (com.orthoflow.messaging.domain.model.MessageStatus.QUEUED,
                                   com.orthoflow.messaging.domain.model.MessageStatus.SENDING)
              AND m.channel <> com.orthoflow.messaging.domain.model.MessageChannel.IN_APP
            """)
    int purgeBodiesBefore(@Param("before") OffsetDateTime before, @Param("now") OffsetDateTime now);

    /** Messages stuck SENDING (the process died mid-send) go back to the queue. */
    @Modifying
    @Query("""
            UPDATE OutboxMessage m SET m.status = com.orthoflow.messaging.domain.model.MessageStatus.QUEUED
            WHERE m.status = com.orthoflow.messaging.domain.model.MessageStatus.SENDING AND m.nextAttemptAt < :before
            """)
    int requeueStuck(@Param("before") OffsetDateTime before);
}
