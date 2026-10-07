package com.orthoflow.treatment.application.port;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * The published port billing uses to read what a mutual insurer needs to see about an act, instead
 * of reaching into the treatment catalogue's entities. Keyed by the clinic's own treatment code,
 * which is what an invoice line carries.
 */
public interface TreatmentActLookup {

    /** The insurer's act code and coefficient, either of which may be unset: they are left blank until the clinic confirms its coding. */
    record Act(String actCode, BigDecimal coefficient) {
    }

    /** Only the codes that name a treatment appear in the result. */
    Map<String, Act> byTreatmentCodes(Collection<String> treatmentCodes);
}
