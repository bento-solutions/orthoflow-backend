package com.orthoflow.messaging.application.service;

import com.orthoflow.messaging.infrastructure.MessagingProperties;
import com.orthoflow.messaging.infrastructure.persistence.OutboxJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

/**
 * Blanks the text of messages older than the retention window (Law 09-08: keep
 * what a phone was told no longer than needed). The delivery record — who, when,
 * which purpose, whether it arrived — stays for the log.
 */
@Component
public class MessageRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(MessageRetentionJob.class);

    private final OutboxJpaRepository outbox;
    private final MessagingProperties properties;
    private final TransactionTemplate tx;

    public MessageRetentionJob(OutboxJpaRepository outbox, MessagingProperties properties, TransactionTemplate tx) {
        this.outbox = outbox;
        this.properties = properties;
        this.tx = tx;
    }

    @Scheduled(cron = "${orthoflow.messaging.retention-cron:0 30 3 * * *}")
    public void run() {
        int days = properties.getRetentionDays();
        if (days <= 0) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        Integer purged = tx.execute(s -> outbox.purgeBodiesBefore(now.minusDays(days), now));
        if (purged != null && purged > 0) {
            log.info("Purged the text of {} messages older than {} days", purged, days);
        }
    }
}
