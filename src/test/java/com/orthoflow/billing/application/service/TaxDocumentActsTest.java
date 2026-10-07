package com.orthoflow.billing.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orthoflow.treatment.application.port.TreatmentActLookup.Act;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * What an insurer reads on a care form. The clinic's coding is confirmed treatment by treatment, so
 * a form must print the insurer's code and coefficient where they are set and nothing invented where
 * they are not.
 */
class TaxDocumentActsTest {

    @Test
    void theInsurersCodeReplacesTheInvoiceLinesOnceTheClinicHasSetIt() {
        assertThat(TaxDocumentService.insurerActCode("ORTHO-01", new Act("TO 90", new BigDecimal("90.00")))).isEqualTo("TO 90");
    }

    @Test
    void theInvoiceLinesCodeStandsWhenNoInsurerCodeIsSet() {
        assertThat(TaxDocumentService.insurerActCode("ORTHO-01", null)).isEqualTo("ORTHO-01");
        assertThat(TaxDocumentService.insurerActCode("ORTHO-01", new Act(null, null))).isEqualTo("ORTHO-01");
        assertThat(TaxDocumentService.insurerActCode("ORTHO-01", new Act("  ", new BigDecimal("5")))).isEqualTo("ORTHO-01");
    }

    @Test
    void aCoefficientIsPrintedWithoutTrailingZerosAndLeftBlankWhenUnset() {
        assertThat(TaxDocumentService.coefficientText(new Act("X", new BigDecimal("90.00")))).isEqualTo("90");
        assertThat(TaxDocumentService.coefficientText(new Act("X", new BigDecimal("12.50")))).isEqualTo("12.5");
        assertThat(TaxDocumentService.coefficientText(new Act("X", null))).isNull();
        assertThat(TaxDocumentService.coefficientText(null)).isNull();
    }
}
