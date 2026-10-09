package com.orthoflow.common.numbering;

import com.orthoflow.common.tenancy.CurrentPractice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The next number of a clinic's document series (invoices, purchase orders, patient
 * codes...). Each clinic counts on its own, so its numbering has no gaps left by
 * another clinic sharing the deployment (ADR 0007).
 *
 * <p>The counter row stays locked until the caller's transaction ends, so two
 * documents of one clinic never get the same number, and a rolled-back document
 * gives its number back.
 */
@Component
public class DocumentNumbers {

    private final JdbcTemplate jdbc;
    private final CurrentPractice currentPractice;

    public DocumentNumbers(JdbcTemplate jdbc, CurrentPractice currentPractice) {
        this.jdbc = jdbc;
        this.currentPractice = currentPractice;
    }

    /** The next number for the clinic the current work runs as. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String series) {
        return next(currentPractice.require(), series);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long next(UUID practiceId, String series) {
        Long value = jdbc.queryForObject("""
                INSERT INTO document_counters (practice_id, series, last_value) VALUES (?, ?, 1)
                ON CONFLICT (practice_id, series) DO UPDATE SET last_value = document_counters.last_value + 1
                RETURNING last_value
                """, Long.class, practiceId, series);
        return value;
    }
}
