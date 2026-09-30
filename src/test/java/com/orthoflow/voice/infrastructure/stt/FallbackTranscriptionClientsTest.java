package com.orthoflow.voice.infrastructure.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback recognisers' defences against what they produce from noise.
 * Every case here is one measured on consultation audio with handpiece and
 * suction noise — a hallucinated transcript is read back as a finding.
 */
class FallbackTranscriptionClientsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SpeechToTextProperties properties;
    private VoiceProviderProperties vendors;
    private GroqTranscriptionClient whisper;
    private AssemblyAiTranscriptionClient assembly;

    @BeforeEach
    void setUp() {
        properties = new SpeechToTextProperties();
        vendors = new VoiceProviderProperties();
        whisper = new GroqTranscriptionClient(properties, vendors, objectMapper);
        assembly = new AssemblyAiTranscriptionClient(properties, vendors, objectMapper);
    }

    // ── Whisper ─────────────────────────────────────────────────────────

    @Test
    void whisperReciteingItsPromptIsDroppedButRealDictationIsKept() {
        assertThat(GroqTranscriptionClient.isPromptEcho(
                "Dictée dentaire, abcès, mobilité, poche parodontale, récession gingivale, obturation composite."))
                .isTrue();
        // Four findings, but on a named tooth: dictation.
        assertThat(GroqTranscriptionClient.isPromptEcho(
                "Dent seize, abcès, mobilité, poche parodontale, récession gingivale.")).isFalse();
        assertThat(GroqTranscriptionClient.isPromptEcho("Calypso, dent 16, carie récurrente occlusale.")).isFalse();
    }

    @Test
    void whisperSegmentsItScoresAsNoSpeechAreDropped() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "text", "Merci. Dent seize, carie.",
                "language", "french",
                "segments", List.of(
                        Map.of("text", " Merci.", "no_speech_prob", 0.92, "avg_logprob", -0.3),
                        Map.of("text", " Dent seize, carie.", "no_speech_prob", 0.02, "avg_logprob", -0.2),
                        Map.of("text", " la la la", "no_speech_prob", 0.1, "avg_logprob", -1.7))));

        TranscriptionResult result = whisper.parse(objectMapper.readTree(body));

        assertThat(result.text()).isEqualTo("Dent seize, carie.");
    }

    @Test
    void whisperUsesTheGroqVendorKeyOrTheSttKeyWhenGroqIsPrimary() {
        assertThat(whisper.isConfigured()).isFalse();
        properties.setProvider("groq");
        properties.setApiKey("gsk-stage");
        assertThat(whisper.isConfigured()).isTrue();

        properties.setProvider("gemini");
        assertThat(whisper.isConfigured()).isFalse();
        vendors.getGroq().setApiKey("gsk-vendor");
        assertThat(whisper.isConfigured()).isTrue();
    }

    // ── AssemblyAI ──────────────────────────────────────────────────────

    @Test
    void assemblyTranscriptsBelowTheConfidenceFloorAreTreatedAsNoise() throws Exception {
        TranscriptionResult noise = assembly.parse(objectMapper.readTree(
                "{\"text\":\"Bienvenue dans le centre dentaire.\",\"confidence\":0.45,\"audio_duration_ms\":2100}"), "fr");
        TranscriptionResult speech = assembly.parse(objectMapper.readTree(
                "{\"text\":\"Calypso, dent 16, carie récurrente occlusale.\",\"confidence\":0.93}"), "fr");

        assertThat(noise.error()).isNull();
        assertThat(noise.text()).isEmpty();
        assertThat(noise.durationSeconds()).isEqualTo(2.1);
        assertThat(speech.text()).isEqualTo("Calypso, dent 16, carie récurrente occlusale.");
        assertThat(speech.model()).isEqualTo(AssemblyAiTranscriptionClient.MODEL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void assemblyGetsAScenarioAShortKeyTermListAndThePatientsName() {
        Map<String, Object> config = assembly.config("Calypso; patient Karim Benali; dent 16; carie récurrente");

        assertThat((String) config.get("prompt")).contains("dentiste").endsWith("Patient : Karim Benali.");
        List<String> terms = (List<String>) config.get("keyterms_prompt");
        assertThat(terms).contains("Calypso", "carie récurrente").hasSizeLessThanOrEqualTo(100);
        // Common phrases made the model emit them from noise.
        assertThat(terms).doesNotContain("needs a crown", "missing", "normal");
    }

    @Test
    void assemblyRefusesAnythingButWavRatherThanFailingUpstream() {
        vendors.getAssemblyai().setApiKey("aai-key");
        TranscriptionResult result = assembly.transcribe(new byte[] {1, 2}, "clip.webm", "audio/webm", "fr", null);
        assertThat(result.error()).isEqualTo("stt-unsupported-format");
    }
}
