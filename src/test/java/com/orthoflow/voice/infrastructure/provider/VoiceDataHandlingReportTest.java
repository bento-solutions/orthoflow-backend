package com.orthoflow.voice.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import com.orthoflow.voice.infrastructure.nlu.VoiceNluProperties;
import com.orthoflow.voice.infrastructure.stt.SpeechToTextProperties;
import com.orthoflow.voice.infrastructure.summary.VoiceSummaryProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceDataHandlingReportTest {

    private VoiceProviderProperties vendors;
    private SpeechToTextProperties stt;
    private VoiceNluProperties nlu;
    private VoiceSummaryProperties summary;
    private ConsultationExtractionProperties consultation;

    @BeforeEach
    void setUp() {
        vendors = new VoiceProviderProperties();
        stt = new SpeechToTextProperties();
        nlu = new VoiceNluProperties();
        summary = new VoiceSummaryProperties();
        consultation = new ConsultationExtractionProperties();
    }

    private VoiceDataHandlingReport report() {
        return new VoiceDataHandlingReport(vendors, stt, nlu, summary, consultation);
    }

    @Test
    void saysNothingWhileNothingLeavesTheMachine() {
        vendors.getGemini().setApiKey("key");
        vendors.getAssemblyai().setApiKey("key");

        assertThat(report().unattested()).isEmpty();
    }

    @Test
    void namesEveryKeyedVendorThatReceivesAudioAndIsNotAttested() {
        stt.setEnabled(true);
        stt.setProvider("assemblyai");
        stt.setFallbacks(List.of("gemini", "groq"));
        vendors.getAssemblyai().setApiKey("k");
        vendors.getGemini().setApiKey("k");
        // groq has no key, so nothing can be sent to it.

        assertThat(report().unattested()).isEqualTo(Map.of(
                "assemblyai", List.of("audio transcription"),
                "gemini", List.of("audio transcription")));
    }

    @Test
    void anAttestedVendorIsLeftOut() {
        stt.setEnabled(true);
        stt.setProvider("assemblyai");
        stt.setFallbacks(List.of("gemini"));
        vendors.getAssemblyai().setApiKey("k");
        vendors.getAssemblyai().setDataProtected(true);
        vendors.getGemini().setApiKey("k");

        assertThat(report().unattested()).containsOnlyKeys("gemini");
    }

    @Test
    void listsEachStageAVendorServes() {
        stt.setEnabled(true);
        stt.setProvider("groq");
        stt.setFallbacks(List.of());
        nlu.getNlu().setProvider("groq");
        nlu.getNlu().setFallbacks(List.of());
        summary.setEnabled(true);
        summary.setProvider("groq");
        summary.setFallbacks(List.of("deepseek:deepseek-flash"));
        vendors.getGroq().setApiKey("k");

        assertThat(report().unattested().get("groq"))
                .containsExactly("audio transcription", "command interpretation", "consultation summary");
    }

    @Test
    void aDisabledStageSendsNothing() {
        nlu.getNlu().setProvider("disabled");
        nlu.getNlu().setFallbacks(List.of("gemini"));
        vendors.getGemini().setApiKey("k");

        // "Turning the fallback on is not a way to send transcripts to a vendor."
        assertThat(report().receivers()).isEmpty();
    }

    @Test
    void aConsultationReadingSendsTheWholeConversationAndNamesEveryVendorInItsChain() {
        consultation.setEnabled(true);
        consultation.getExtraction().setEnabled(true);
        consultation.getExtraction().setProvider("groq");
        consultation.getExtraction().setFallbacks(List.of("deepseek:deepseek-flash"));
        vendors.getGroq().setApiKey("k");
        vendors.getDeepseek().setApiKey("k");

        assertThat(report().unattested()).isEqualTo(Map.of(
                "groq", List.of("consultation transcript reading"),
                "deepseek", List.of("consultation transcript reading")));
    }

    @Test
    void consultationReadingSendsNothingWhileTheFeatureItselfIsOff() {
        consultation.setEnabled(false);
        consultation.getExtraction().setEnabled(true);
        vendors.getGroq().setApiKey("k");

        assertThat(report().unattested()).isEmpty();
    }
}
