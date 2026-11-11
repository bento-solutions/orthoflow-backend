package com.orthoflow.consultation.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orthoflow.consultation.infrastructure.extraction.ConsultationPromptBuilder.CatalogEntry;
import com.orthoflow.voice.infrastructure.provider.ChatCompletionClient;
import com.orthoflow.voice.infrastructure.provider.ChatCompletionClient.Result;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The tail reading (docs/VOICE-COCKPIT-PLAN.md WP6): the end of the
 * conversation, read against what is already known, every few seconds. Checked
 * on answers a model could give, the right ones and the wrong ones.
 */
class TailReadingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final String TAIL = """
            Pour les allergies, vous avez une allergie à l'ananas, vous avez une allergie à la pénicilline.
            Pas diabète de type 2, diabète de type 1. OK, OK, diabète de type 1.
            Donc vous avez dans 16 caries occlusales.""";
    private static final List<String> KNOWN = List.of("Diabète de type 2");
    private static final UUID SCALING = UUID.randomUUID();
    private static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry(SCALING, "DET", "Détartrage", new BigDecimal("300")),
            new CatalogEntry(UUID.randomUUID(), "CMP3", "Composite trois faces", new BigDecimal("600")));

    private ConsultationExtractionProperties properties;
    private VoiceProviderProperties vendors;
    private ChatCompletionClient chat;
    private ConsultationExtractor extractor;

    @BeforeEach
    void setUp() {
        properties = new ConsultationExtractionProperties();
        properties.setEnabled(true);
        properties.getExtraction().setEnabled(true);
        properties.getExtraction().setProvider("groq");
        properties.getExtraction().setModel("big");
        properties.getExtraction().setFallbacks(List.of());
        properties.getExtraction().getTail().setEnabled(true);
        vendors = new VoiceProviderProperties();
        vendors.setRequireDataProtection(false);
        vendors.getGroq().setApiKey("g-key");
        chat = mock(ChatCompletionClient.class);
        extractor = new ConsultationExtractor(properties, vendors, chat);
    }

    @Test
    void readsWhatIsNewAndWhatIsCorrected() {
        String answer = """
                {"allergies": [{"substance": "ananas", "quote": "vous avez une allergie à l'ananas"}],
                 "corrections": [{"list": "medicalHistory", "was": "Diabète de type 2",
                   "now": {"category": "CONDITION", "label": "Diabète de type 1"}, "quote": "Pas diabète de type 2, diabète de type 1"}],
                 "chartMentions": [{"teeth": "16", "finding": "carie occlusale", "quote": "dans 16 caries occlusales"}]}""";
        ConsultationDraftParser.Result parsed = ConsultationDraftParser.parseTail(answer, TAIL, List.of(), TODAY, "m", KNOWN);

        assertThat(parsed.draft().allergies()).extracting(a -> a.substance()).containsExactly("ananas");
        assertThat(parsed.corrections()).singleElement().satisfies(c -> {
            assertThat(c.was()).isEqualTo("Diabète de type 2");
            assertThat(c.nowLabel()).isEqualTo("Diabète de type 1");
        });
        assertThat(parsed.chartMentions()).singleElement().satisfies(m -> {
            assertThat(m.teeth()).isEqualTo("16");
            assertThat(m.finding()).isEqualTo("carie occlusale");
        });
    }

    @Test
    void dropsACorrectionOfSomethingNeverRead() {
        String answer = """
                {"corrections": [{"list": "medicalHistory", "was": "Hypertension", "now": null, "quote": "Pas diabète de type 2"}]}""";
        assertThat(ConsultationDraftParser.parseTail(answer, TAIL, List.of(), TODAY, "m", KNOWN).corrections()).isEmpty();
    }

    @Test
    void dropsAMentionWhoseWordsDoNotNameTheTooth() {
        String answer = """
                {"chartMentions": [{"teeth": "26", "finding": "carie occlusale", "quote": "dans 16 caries occlusales"},
                                   {"teeth": "16", "finding": "carie", "quote": "une phrase jamais dite"}]}""";
        assertThat(ConsultationDraftParser.parseTail(answer, TAIL, List.of(), TODAY, "m", KNOWN).chartMentions()).isEmpty();
    }

    @Test
    void sendsOnlyTheEndOfTheConversationAndTheLabelsAlreadyRead() {
        properties.getExtraction().getTail().setChars(60);
        when(chat.complete(any())).thenReturn(new Result("{}", null));
        String transcript = "Je m'appelle Achraf Tazi, CIN BK123456.\n" + TAIL;

        extractor.extractTail(transcript, KNOWN, CATALOG, TODAY);

        ArgumentCaptor<ChatCompletionClient.Request> sent = ArgumentCaptor.forClass(ChatCompletionClient.Request.class);
        verify(chat).complete(sent.capture());
        String user = sent.getValue().user();
        assertThat(user).contains("Diabète de type 2").contains("dans 16 caries occlusales");
        assertThat(user).doesNotContain("Achraf").doesNotContain("BK123456");
        assertThat(sent.getValue().system()).contains("ÉTAT DÉJÀ RELEVÉ").doesNotContain("CATALOGUE");
    }

    @Test
    void anAnswerWithNoRouteIsAnEmptyDraftMarkedFailedNeverTheRulesReading() {
        when(chat.complete(any())).thenReturn(new Result(null, "http-429"));

        ConsultationExtractor.Extraction result = extractor.extractTail(TAIL, KNOWN, CATALOG, TODAY);

        assertThat(result.error()).isEqualTo(ConsultationExtractor.FAILED);
        assertThat(result.mode()).isEqualTo("tail");
        assertThat(result.draft().allergies()).isEmpty();
        assertThat(result.draft().source()).isNotEqualTo(RuleBasedExtractor.SOURCE);
    }

    @Test
    void isOffUntilSwitchedOn() {
        properties.getExtraction().getTail().setEnabled(false);
        assertThat(extractor.extractTail(TAIL, KNOWN, CATALOG, TODAY).error()).isEqualTo(ConsultationExtractor.DISABLED);
        verify(chat, never()).complete(any());
    }

    @Test
    void givesATailPlanLineItsActFromTheCatalogue() {
        when(chat.complete(any())).thenReturn(new Result("""
                {"treatmentPlan": [{"label": "détartrage complet", "price": null, "quote": "on fera un détartrage complet"}]}""", null));
        ConsultationExtractor.Extraction result = extractor.extractTail("Bon, on fera un détartrage complet.", KNOWN, CATALOG, TODAY);
        assertThat(result.draft().treatmentPlan()).singleElement().satisfies(line -> {
            assertThat(line.treatmentId()).isEqualTo(SCALING);
            assertThat(line.price()).isEqualByComparingTo("300");
            assertThat(line.priceSource()).isEqualTo("CATALOG");
        });
    }

    @Test
    void cutsTheTailAtALineSoNoSentenceStartsHalfway() {
        assertThat(ConsultationExtractor.tailOf("aaaa\nbbbb\ncccc", 7)).isEqualTo("cccc");
        assertThat(ConsultationExtractor.tailOf("court", 100)).isEqualTo("court");
    }

    @Test
    void matchesAPlanLineToTheCatalogueByNameThenByEveryWord() {
        assertThat(CatalogMatcher.match("Détartrage", CATALOG).id()).isEqualTo(SCALING);
        assertThat(CatalogMatcher.match("un détartrage complet", CATALOG).id()).isEqualTo(SCALING);
        assertThat(CatalogMatcher.match("composite deux faces", CATALOG)).isNull();
        assertThat(CatalogMatcher.match("", CATALOG)).isNull();
    }
}
