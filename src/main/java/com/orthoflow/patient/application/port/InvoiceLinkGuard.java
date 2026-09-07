package com.orthoflow.patient.application.port;

import java.util.UUID;

/**
 * Lets {@code PatientService} ask billing "does this patient still have
 * invoices?" without the patient module depending on billing types.
 *
 * <p>Invoices reference the patient with {@code ON DELETE RESTRICT} (migration
 * V15, deliberately): a financial record carries its own accounting-law
 * retention obligation, independent of the patient's GDPR/Law 09-08 erasure
 * right, so an erasure must not silently orphan or destroy it. Before V15's
 * intent was surfaced here, {@code /patients/{id}/erase} for an invoiced
 * patient failed with a generic {@code 409 "This action conflicts with
 * existing data."} — this port lets the service explain the real reason and
 * what to do about it instead.
 */
public interface InvoiceLinkGuard {

    /** Number of invoices (any status) that still reference this patient. */
    long countInvoicesForPatient(UUID patientId);
}
