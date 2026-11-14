package com.orthoflow.consultation.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.orthoflow.consultation.domain.model.ConsultationDraft;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationPromptBuilder.CatalogEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a reading may say about the patient's account: an act done today (which the save can
 * invoice) and money handed over (which becomes a receipt once validated). A receipt is money in
 * the clinic's books, so a payment is held to a quote that says the amount in figures.
 */
class ConsultationFinancialReadingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final UUID DETARTRAGE = UUID.randomUUID();

    private static final String TRANSCRIPT = """
            Bon, je vous fais le détartrage maintenant. Voilà, c'est fait. Le détartrage c'est 300 dirhams. \
            Pour la 16 il faudra une couronne, comptez 3 500 dirhams. Je vous règle 300 dirhams en espèces. \
            Et je vous donnerai le reste la prochaine fois, deux mille.""";

    private static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry(DETARTRAGE, "DETART", "Détartrage", new BigDecimal("250.00")));

    private static ConsultationDraft read(String payments, String plan) {
        String json = """
                {"patient": {}, "chiefComplaint": null, "activeTreatments": [], "allergies": [], "medicalHistory": [],
                 "treatmentPlan": %s, "nextAppointment": null, "chartMentions": [], "payments": %s}
                """.formatted(plan, payments);
        return ConsultationDraftParser.parse(json, TRANSCRIPT, CATALOG, TODAY, "groq:test").draft();
    }

    @Test
    void anActDoneDuringTheConsultationIsMarkedPerformedAndOneForLaterIsNot() {
        ConsultationDraft draft = read("[]", """
                [{"label": "Détartrage", "treatmentCode": "DETART", "price": 300, "performed": true,
                  "quote": "je vous fais le détartrage maintenant"},
                 {"label": "Couronne", "teeth": "16", "price": 3500, "performed": false,
                  "quote": "Pour la 16 il faudra une couronne"}]""");

        assertThat(draft.treatmentPlan()).extracting(ConsultationDraft.PlanItem::label, ConsultationDraft.PlanItem::performed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Détartrage", true),
                        org.assertj.core.groups.Tuple.tuple("Couronne", false));
    }

    @Test
    void aPlanLineThatDoesNotSayIsProposedForLater() {
        ConsultationDraft draft = read("[]", """
                [{"label": "Détartrage", "treatmentCode": "DETART", "quote": "je vous fais le détartrage maintenant"}]""");

        assertThat(draft.treatmentPlan()).singleElement().extracting(ConsultationDraft.PlanItem::performed).isEqualTo(false);
    }

    @Test
    void aPaymentSaidInFiguresIsReadWithItsMethod() {
        ConsultationDraft draft = read("""
                [{"amount": 300, "method": "cash", "quote": "Je vous règle 300 dirhams en espèces"}]""", "[]");

        assertThat(draft.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.amount()).isEqualByComparingTo("300");
            assertThat(payment.method()).isEqualTo("CASH");
            assertThat(payment.key()).isEqualTo("payment:300:CASH");
        });
    }

    @Test
    void aPaymentWhoseQuoteDoesNotCarryTheAmountIsDropped() {
        // The amount is said elsewhere (the price of the scaling), but not in the words given as evidence.
        ConsultationDraft draft = read("""
                [{"amount": 300, "method": "CASH", "quote": "Voilà, c'est fait"}]""", "[]");

        assertThat(draft.payments()).isEmpty();
    }

    @Test
    void anAmountSaidOnlyInWordsCannotBeCheckedAndIsLeftToTheDoctor() {
        ConsultationDraft draft = read("""
                [{"amount": 2000, "method": null, "quote": "je vous donnerai le reste la prochaine fois, deux mille"}]""", "[]");

        assertThat(draft.payments()).isEmpty();
    }

    @Test
    void aQuoteThatIsNotInTheConversationIsNoPayment() {
        ConsultationDraft draft = read("""
                [{"amount": 500, "method": "CARD", "quote": "je paie 500 par carte"}]""", "[]");

        assertThat(draft.payments()).isEmpty();
    }

    @Test
    void anUnknownMethodIsLeftEmptyRatherThanGuessed() {
        ConsultationDraft draft = read("""
                [{"amount": 300, "method": "BITCOIN", "quote": "Je vous règle 300 dirhams en espèces"}]""", "[]");

        assertThat(draft.payments()).singleElement().satisfies(payment -> assertThat(payment.method()).isNull());
    }

    @Test
    void anAnswerWithoutAPaymentsSectionIsStillAReading() {
        String json = """
                {"patient": {}, "chiefComplaint": null, "activeTreatments": [], "allergies": [], "medicalHistory": [],
                 "treatmentPlan": [], "nextAppointment": null}""";
        ConsultationDraftParser.Result result = ConsultationDraftParser.parse(json, TRANSCRIPT, CATALOG, TODAY, "groq:test");

        assertThat(result).isNotNull();
        assertThat(result.draft().payments()).isEmpty();
    }

    @Test
    void thePromptAsksForTheActsDoneTodayAndThePayments() {
        String system = ConsultationPromptBuilder.system("français", CATALOG);

        assertThat(system).contains("\"performed\"").contains("\"payments\"").contains("Un prix annoncé pour un acte n'est pas un");
    }
}
