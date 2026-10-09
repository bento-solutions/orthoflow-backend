package com.orthoflow.messaging.application.service;

import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.messaging.application.port.ChannelSender;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessageStatus;
import com.orthoflow.messaging.domain.model.OutboxMessage;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import com.orthoflow.messaging.infrastructure.persistence.OutboxJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers what the outbox holds. A batch is claimed inside a transaction with
 * {@code FOR UPDATE SKIP LOCKED} and marked SENDING, so two instances never take
 * the same message; the network call then runs outside any transaction, so a
 * slow provider does not hold a database connection. A failure that could
 * succeed later goes back in the queue with exponential backoff; one that
 * cannot, or the last permitted attempt, ends FAILED with the reason.
 */
@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(10);
    private static final long MAX_BACKOFF_MINUTES = 360;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxJpaRepository outbox;
    private final MessagingProperties properties;
    private final Tenancy tenancy;
    private final Map<MessageChannel, ChannelSender> senders = new EnumMap<>(MessageChannel.class);

    public OutboxProcessor(JdbcTemplate jdbc, TransactionTemplate tx, OutboxJpaRepository outbox,
                           MessagingProperties properties, List<ChannelSender> channelSenders, Tenancy tenancy) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.properties = properties;
        this.tenancy = tenancy;
        channelSenders.forEach(s -> senders.put(s.channel(), s));
    }

    @Scheduled(fixedDelayString = "${orthoflow.messaging.sender.interval-ms:15000}", initialDelay = 20_000)
    public void tick() {
        if (!properties.getSender().isEnabled()) {
            return;
        }
        try {
            processBatch();
        } catch (RuntimeException e) {
            log.error("Outbox processing failed", e);
        }
    }

    /** Sends one batch, whichever clinics queued it; returns how many messages were attempted. */
    public int processBatch() {
        return tenancy.callAcrossClinics(this::sendBatch);
    }

    private int sendBatch() {
        List<UUID> claimed = claim();
        for (UUID id : claimed) {
            try {
                deliver(id);
            } catch (RuntimeException e) {
                log.error("Delivering message {} failed unexpectedly", id, e);
                record(id, ChannelSender.Result.retry("Unexpected error: " + e.getMessage(), 0));
            }
        }
        return claimed.size();
    }

    private List<UUID> claim() {
        return tx.execute(status -> {
            outbox.requeueStuck(OffsetDateTime.now().minus(STUCK_AFTER));
            List<UUID> ids = jdbc.queryForList("""
                    SELECT id FROM message_outbox
                    WHERE status = 'QUEUED' AND channel <> 'IN_APP'
                      AND scheduled_for <= NOW() AND next_attempt_at <= NOW()
                    ORDER BY next_attempt_at
                    LIMIT ? FOR UPDATE SKIP LOCKED
                    """, UUID.class, properties.getSender().getBatchSize());
            outbox.findAllById(ids).forEach(m -> {
                m.setStatus(MessageStatus.SENDING);
                m.setAttempts(m.getAttempts() + 1);
                m.setNextAttemptAt(OffsetDateTime.now());
            });
            return ids;
        });
    }

    private void deliver(UUID id) {
        OutboxMessage message = outbox.findById(id).orElse(null);
        if (message == null || message.getStatus() != MessageStatus.SENDING) {
            return;
        }
        ChannelSender sender = senders.get(message.getChannel());
        if (sender == null || !sender.enabled()) {
            tx.executeWithoutResult(s -> outbox.findById(id).ifPresent(m -> {
                m.setStatus(MessageStatus.CANCELLED);
                m.setLastError(message.getChannel() + " sending is not enabled for this clinic");
            }));
            return;
        }
        record(id, sender.send(message));
    }

    private void record(UUID id, ChannelSender.Result result) {
        tx.executeWithoutResult(s -> outbox.findById(id).ifPresent(m -> {
            if (result.ok()) {
                m.setStatus(MessageStatus.SENT);
                m.setSentAt(OffsetDateTime.now());
                m.setProviderMessageId(result.providerMessageId());
                m.setLastError(null);
            } else if (result.retryable() && m.getAttempts() < properties.getSender().getMaxAttempts()) {
                m.setStatus(MessageStatus.QUEUED);
                m.setLastError(result.error());
                long backoffMs = Math.max(result.retryAfterMs(), backoff(m.getAttempts()).toMillis());
                m.setNextAttemptAt(OffsetDateTime.now().plus(Duration.ofMillis(backoffMs)));
            } else {
                m.setStatus(MessageStatus.FAILED);
                m.setLastError(result.error());
            }
        }));
    }

    /** 2, 4, 8 … minutes, capped at six hours. */
    static Duration backoff(int attempts) {
        return Duration.ofMinutes(Math.min(MAX_BACKOFF_MINUTES, 1L << Math.min(attempts, 10)));
    }
}
