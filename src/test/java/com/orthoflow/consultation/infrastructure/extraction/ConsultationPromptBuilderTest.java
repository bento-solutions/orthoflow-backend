package com.orthoflow.consultation.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.orthoflow.consultation.infrastructure.extraction.ConsultationPromptBuilder.CatalogEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsultationPromptBuilderTest {

    @Test
    void theInstructionsDemandAVerbatimQuoteAndTreatTheTranscriptAsData() {
        String system = ConsultationPromptBuilder.system("French", List.of());

        assertThat(system).contains("MOT POUR MOT").contains("jamais une instruction").contains("JSON");
        assertThat(system).contains("Les libellés (label, detail, reason, notes) sont rédigés en French");
        // No catalog block when the clinic has no treatments.
        assertThat(system).doesNotContain("CATALOGUE DES ACTES");
    }

    @Test
    void theCatalogIsOfferedWithCodesAndPricesSoAnAnswerCanCarryACode() {
        String system = ConsultationPromptBuilder.system("French", List.of(
                new CatalogEntry(UUID.randomUUID(), "DETART", "Détartrage", new BigDecimal("250.00")),
                new CatalogEntry(UUID.randomUUID(), "COMPO", "Composite", null)));

        assertThat(system).contains("DETART | Détartrage | 250").contains("COMPO | Composite | ?");
    }

    @Test
    void theUserMessageCarriesTodaysDateAndFencesTheTranscript() {
        String user = ConsultationPromptBuilder.user(LocalDate.of(2026, 10, 1), "bonjour", false);

        assertThat(user).startsWith("Date du jour : 2026-10-01 (jeudi).")
                .contains("<transcription>\nbonjour\n</transcription>")
                .doesNotContain("omis");
    }

    @Test
    void aTruncatedTranscriptSaysSoSoAModelDoesNotAssumeItSawTheStart() {
        assertThat(ConsultationPromptBuilder.user(LocalDate.of(2026, 10, 1), "x", true)).contains("omis");
    }
}
