package com.orthoflow.consultation.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** What counts as "this was really said" — the test every extracted claim must pass. */
class TranscriptTextTest {

    private static final String TRANSCRIPT = TranscriptText.normalize(
            "Bonjour docteur. Mon numéro, c'est le 06 12 34 56 78. Je suis allergique à la pénicilline, "
                    + "j'ai fait une réaction il y a deux ans.");

    @Test
    void normalisationRemovesAccentsCaseAndPunctuation() {
        assertThat(TranscriptText.normalize("  C'est l'ÉTÉ,   là !  ")).isEqualTo("c est l ete la");
        assertThat(TranscriptText.normalize(null)).isEmpty();
    }

    @Test
    void anExactQuoteIsSupportedWhateverTheCapitalsAndPunctuation() {
        assertThat(TranscriptText.isSupported("Je suis allergique à la pénicilline", TRANSCRIPT)).isTrue();
        assertThat(TranscriptText.isSupported("JE SUIS ALLERGIQUE A LA PENICILLINE.", TRANSCRIPT)).isTrue();
    }

    @Test
    void aQuoteThatIsNotInTheConversationIsNotSupported() {
        assertThat(TranscriptText.isSupported("Je suis allergique à l'aspirine", TRANSCRIPT)).isFalse();
        assertThat(TranscriptText.isSupported("", TRANSCRIPT)).isFalse();
        assertThat(TranscriptText.isSupported("anything", "")).isFalse();
    }

    @Test
    void aLongQuoteThatLostOneWordWhenCopiedStillCounts() {
        // The recogniser wrote "j'ai fait une réaction"; the model copied "j'ai fait réaction".
        assertThat(TranscriptText.isSupported(
                "je suis allergique à la pénicilline j'ai fait réaction il y a deux ans", TRANSCRIPT)).isTrue();
    }

    @Test
    void aLongQuoteWhoseWordsAreScatteredOrMostlyMadeUpIsRefused() {
        assertThat(TranscriptText.isSupported(
                "je suis allergique à la codéine et à l'aspirine depuis toujours", TRANSCRIPT)).isFalse();
        assertThat(TranscriptText.isSupported(
                "bonjour docteur ans pénicilline numéro réaction", TRANSCRIPT)).isFalse();
    }

    @Test
    void aShortQuoteMustMatchExactlyBecauseMostlyThereIsNotEvidence() {
        assertThat(TranscriptText.isSupported("allergique pénicilline", TRANSCRIPT)).isFalse();
    }

    @Test
    void anExactQuoteMustBeWholeWordsNotAFragmentOfOne() {
        // "an" is inside "ans"; "e" is inside almost everything.
        assertThat(TranscriptText.isSupported("an", TranscriptText.normalize("j'ai 34 ans"))).isFalse();
        assertThat(TranscriptText.isSupported("e", TRANSCRIPT)).isFalse();
        assertThat(TranscriptText.isSupported("docteur", TRANSCRIPT)).isTrue();
    }

    @Test
    void aShortQuoteBacksOnlyTheValueItContains() {
        assertThat(TranscriptText.backs("oui", "pénicilline")).isFalse();
        assertThat(TranscriptText.backs("d'accord", "Diabète")).isFalse();
        assertThat(TranscriptText.backs("Pénicilline", "pénicilline")).isTrue();
        assertThat(TranscriptText.backs("la CNOPS", "CNOPS")).isTrue();
        // A sentence is context for what was read from it, reworded or not.
        assertThat(TranscriptText.backs("je suis diabétique depuis dix ans", "Diabète")).isTrue();
    }

    @Test
    void aValuesDigitsMustBeTheQuotes() {
        assertThat(TranscriptText.digitsMatch("c'est le 06 12 34 56 78", "0612345678")).isTrue();
        assertThat(TranscriptText.digitsMatch("c'est le 06 12 34 56 78", "+212612345678")).isTrue();
        assertThat(TranscriptText.digitsMatch("c'est le 06 12 34 56 78", "0612345679")).isFalse();
        assertThat(TranscriptText.digitsMatch("mon numéro c'est le", "0612345678")).isTrue(); // said in words: uncheckable
    }

    @Test
    void anAmountMustBeANumberSaidInTheConversation() {
        String said = TranscriptText.normalize("Le détartrage c'est 300 dirhams, la couronne 3 500, le reste 1.200,50.");
        assertThat(TranscriptText.saysAmount(said, new BigDecimal("300"))).isTrue();
        assertThat(TranscriptText.saysAmount(said, new BigDecimal("300.00"))).isTrue();
        assertThat(TranscriptText.saysAmount(said, new BigDecimal("3500"))).isTrue();
        assertThat(TranscriptText.saysAmount(said, new BigDecimal("1200.50"))).isTrue();
        assertThat(TranscriptText.saysAmount(said, new BigDecimal("250"))).isFalse();
        assertThat(TranscriptText.saysAmount(said, new BigDecimal("500"))).isTrue(); // a group on its own is still a number said
    }
}
