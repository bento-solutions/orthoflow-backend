package com.orthoflow.billing.application.port;

import java.util.UUID;

/**
 * A veto over changing which practitioner an invoice belongs to. The invoice's
 * practitioner decides whose retrocession it counts toward, so once a validated
 * statement has paid someone on the strength of an invoice, moving that invoice
 * to a colleague would pay it twice. The retrocession module answers.
 */
public interface InvoiceAttributionGuard {

    /** @throws com.orthoflow.common.exception.ConflictException when a validated statement already counts the invoice */
    void assertReassignable(UUID invoiceId);
}
