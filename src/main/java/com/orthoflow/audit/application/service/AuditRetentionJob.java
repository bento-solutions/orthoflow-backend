package com.orthoflow.audit.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes audit rows past their retention (Law 09-08: keep personal data no longer than needed, and an
 * access log is personal data about staff and patients). The one thing allowed to delete from an
 * append-only table, and it says so to the table's trigger for this transaction only.
 *
 * <p>Reads of a record are by far the most numerous and the least valuable after a while, so they have
 * their own, shorter, period. A period of 0 or less keeps that kind forever. The defaults are a policy
 * the clinic must confirm against its CNDP declaration; they are not a legal requirement quoted from a text.
 */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int retentionDays;
    private final int readRetentionDays;

    public AuditRetentionJob(JdbcTemplate jdbc, TransactionTemplate tx,
                             @Value("${orthoflow.audit.retention-days:1095}") int retentionDays,
                             @Value("${orthoflow.audit.phi-read-retention-days:365}") int readRetentionDays) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.retentionDays = retentionDays;
        this.readRetentionDays = readRetentionDays;
    }

    @Scheduled(cron = "${orthoflow.audit.retention-cron:0 40 3 * * *}")
    public void run() {
        int purged = purge();
        if (purged > 0) {
            log.info("Purged {} audit rows past their retention", purged);
        }
    }

    /** Returns how many rows were deleted. */
    public int purge() {
        Integer n = tx.execute(s -> {
            jdbc.queryForObject("SELECT set_config('orthoflow.audit_purge', 'on', true)", String.class);
            int deleted = 0;
            if (readRetentionDays > 0) {
                deleted += jdbc.update("DELETE FROM audit_log WHERE category = 'PHI_READ' AND occurred_at < now() - make_interval(days => ?)", readRetentionDays);
            }
            if (retentionDays > 0) {
                deleted += jdbc.update("DELETE FROM audit_log WHERE occurred_at < now() - make_interval(days => ?)", retentionDays);
            }
            return deleted;
        });
        return n == null ? 0 : n;
    }
}
