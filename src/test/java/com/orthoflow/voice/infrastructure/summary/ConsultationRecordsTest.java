package com.orthoflow.voice.infrastructure.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic reading of a consultation — what the model is given, what
 * its answer is checked against, and what the dentist gets when no model is
 * available.
 */
class ConsultationRecordsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ConsultationRecords records(String language, Map.Entry<String, String>... rows) {
        return ConsultationRecords.of(List.of(rows), language, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendersEachWriteInClinicalFrench() {
        ConsultationRecords records = records("French",
                Map.entry("clinical.addFindings", "{\"fdi\":\"36\",\"findings\":[{\"code\":\"deep_caries\","
                        + "\"severity\":\"SEVERE\"},{\"code\":\"root_canal_required\"}]}"),
                Map.entry("clinical.retractFindings", "{\"fdi\":\"46\",\"codes\":[\"caries\"]}"),
                Map.entry("clinical.addNote", "{\"category\":\"FOLLOW_UP\",\"content\":\"dans 6 mois\"}"),
                Map.entry("clinical.addAllergy", "{\"substance\":\"pénicilline\",\"reaction\":\"urticaire\"}"),
                Map.entry("clinical.addMedicalHistory", "{\"category\":\"CONDITION\",\"label\":\"diabète\"}"),
                Map.entry("chart.readTooth", "{\"fdi\":\"11\"}"));

        assertThat(records.entries()).extracting(ConsultationRecords.Entry::line).containsExactly(
                "Dent 36 : carie profonde (sévère) ; traitement canalaire à faire",
                "Retiré de la dent 46 : carie",
                "Contrôle : dans 6 mois",
                "Allergie : pénicilline (réaction : urticaire)",
                "Antécédent médical : diabète");
        assertThat(records.findingTeeth()).containsExactly("36");
        assertThat(records.mentionedTeeth()).containsExactlyInAnyOrder("36", "46");
    }

    @Test
    @SuppressWarnings("unchecked")
    void flagsTeethASummaryInventsOrLeavesOut() {
        ConsultationRecords records = records("French",
                Map.entry("clinical.addFindings", "{\"fdi\":\"16\",\"findings\":[{\"code\":\"caries\"}]}"),
                Map.entry("clinical.addFindings", "{\"fdi\":\"26\",\"findings\":[{\"code\":\"missing\"}]}"));

        assertThat(records.unverifiedTeeth("Dent 16 : carie. Dent 26 : absente. Dent 27 : abcès.")).containsExactly("27");
        assertThat(records.missingTeeth("Dent 16 : carie.")).containsExactly("26");
        assertThat(records.unverifiedTeeth("Dent 16 : carie. Dent 26 : absente.")).isEmpty();
    }

    @Test
    void doesNotMistakeQuantitiesForTeeth() {
        assertThat(ConsultationRecords.toothNumbersIn(
                "Contrôle dans 12 mois, diabète de type 2, sondage 18 mm, à 10:30, 45%, grade 23. Dent 16."))
                .containsExactly("16");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStructuredReportGroupsUnderHeadings() {
        String narrative = records("French",
                Map.entry("clinical.addFindings", "{\"fdi\":\"16\",\"findings\":[{\"code\":\"recurrent_caries\","
                        + "\"surface\":\"occlusal\"}]}"),
                Map.entry("clinical.addAllergy", "{\"substance\":\"latex\"}")).narrative();

        assertThat(narrative).isEqualTo("""
                Constatations par dent
                - Dent 16 : carie récurrente (face occlusale)

                Antécédents et allergies
                - Allergie : latex""");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rendersInEnglishWhenConfiguredSo() {
        ConsultationRecords records = records("English",
                Map.entry("clinical.addFindings", "{\"fdi\":\"16\",\"findings\":[{\"code\":\"recurrent_caries\"}]}"));
        assertThat(records.entries().get(0).line()).isEqualTo("Tooth 16: recurrent caries");
    }
}
