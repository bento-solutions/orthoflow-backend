package com.orthoflow.export.application.dto;

import com.orthoflow.common.exception.IncompleteProfileException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a printed document takes from the clinic profile, and what it refuses to be printed without. */
class LetterheadTest {

    private static final Letterhead COMPLETE = new Letterhead("Cabinet Atlas", "Atlas Dentaire SARL", "001234567000089",
            "40123456", "34567890", "011 780 0000 1234", "12 rue des Orangers", "Casablanca", "0522000000",
            "contact@atlas.ma", null, "123457", "7654321", "987654321", "atlas.ma");

    @Test
    void aCompleteProfileCanIssueEveryLegalDocument() {
        assertThat(COMPLETE.missingFor(Letterhead.Use.FINANCIAL)).isEmpty();
        assertThat(COMPLETE.missingFor(Letterhead.Use.PRESCRIPTION)).isEmpty();
        assertThat(COMPLETE.requireFor(Letterhead.Use.FINANCIAL)).isSameAs(COMPLETE);
    }

    @Test
    void anEmptyProfileNamesEveryFieldEachKindOfDocumentNeeds() {
        Letterhead empty = new Letterhead("Cabinet", null, null, null, null, null, null, null, null, null, null);

        assertThat(empty.missingFor(Letterhead.Use.FINANCIAL)).containsExactly("name", "ice", "taxId", "address", "city");
        assertThat(empty.missingFor(Letterhead.Use.PRESCRIPTION)).containsExactly("name", "address", "city", "phone");
        assertThatThrownBy(() -> empty.requireFor(Letterhead.Use.PRESCRIPTION))
                .isInstanceOfSatisfying(IncompleteProfileException.class,
                        e -> assertThat(e.missing()).containsExactly("name", "address", "city", "phone"));
    }

    @Test
    void aPrescriptionDoesNotNeedTheTaxNumbers() {
        Letterhead noTax = new Letterhead("Cabinet Atlas", null, null, null, null, null, "12 rue des Orangers",
                "Casablanca", "0522000000", null, null);

        assertThat(noTax.missingFor(Letterhead.Use.PRESCRIPTION)).isEmpty();
        assertThat(noTax.missingFor(Letterhead.Use.FINANCIAL)).containsExactly("ice", "taxId");
    }

    @Test
    void theFooterCarriesTheLegalNameAndEveryRegistrationNumber() {
        assertThat(COMPLETE.legalParts()).containsExactly("Atlas Dentaire SARL", "ICE 001234567000089", "IF 40123456",
                "RC 123457", "Patente 34567890", "CNSS 7654321");
        assertThat(COMPLETE.ribLine()).isEqualTo("RIB 011 780 0000 1234");
    }

    @Test
    void theTradingNameStandsInWhenThereIsNoOtherLegalNameAndBlankNumbersAreLeftOut() {
        Letterhead sparse = new Letterhead("Cabinet Atlas", "  ", "001234567000089", " ", null, null, null, null, null, null, null);

        assertThat(sparse.legalParts()).containsExactly("Cabinet Atlas", "ICE 001234567000089");
        assertThat(sparse.ribLine()).isNull();
        assertThat(sparse.addressLine()).isNull();
        assertThat(sparse.contactParts()).isEmpty();
    }

    @Test
    void theHeaderNamesThePractitionerWithTheirInpeOrTheClinicsInpeWhenThereIsNone() {
        assertThat(COMPLETE.addressLine()).isEqualTo("12 rue des Orangers, Casablanca");
        assertThat(COMPLETE.contactParts()).containsExactly("0522000000", "contact@atlas.ma", "atlas.ma");
        assertThat(COMPLETE.practitionerLine("Dr Sara Alaoui", "111222333")).isEqualTo("Dr Sara Alaoui · INPE 111222333");
        assertThat(COMPLETE.practitionerLine("Dr Sara Alaoui", "")).isEqualTo("Dr Sara Alaoui");
        assertThat(COMPLETE.practitionerLine(null, null)).isEqualTo("INPE 987654321");
    }
}
