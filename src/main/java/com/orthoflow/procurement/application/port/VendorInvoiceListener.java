package com.orthoflow.procurement.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Told when a supplier's invoice is validated or cancelled, so another module can
 * react — finance books an expense for it — without procurement knowing that module
 * exists.
 */
public interface VendorInvoiceListener {

    record Summary(UUID id, UUID practiceId, String number, String supplierName, LocalDate invoiceDate,
                   BigDecimal amount, String paymentTerms, UUID validatedBy) {
    }

    void onValidated(Summary invoice);

    void onCancelled(UUID vendorInvoiceId);
}
