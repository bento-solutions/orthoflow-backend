package com.orthoflow.retrocession.infrastructure.adapter;

import com.orthoflow.billing.application.port.InvoiceAttributionGuard;
import com.orthoflow.common.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** An invoice that a live statement already paid on stays with the practitioner it paid. */
@Component
@RequiredArgsConstructor
public class InvoiceAttributionGuardAdapter implements InvoiceAttributionGuard {

    private final JdbcTemplate jdbc;

    @Override
    public void assertReassignable(UUID invoiceId) {
        Integer counted = jdbc.queryForObject("""
                SELECT count(*) FROM retrocession_statement_lines l
                JOIN retrocession_statements s ON s.id = l.statement_id AND s.voided_at IS NULL
                WHERE l.invoice_id = ?""", Integer.class, invoiceId);
        if (counted != null && counted > 0) {
            throw new ConflictException("This invoice is counted on a validated retrocession statement; void the statement before reassigning it");
        }
    }
}
