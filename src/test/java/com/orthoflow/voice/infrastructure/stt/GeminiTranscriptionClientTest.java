package com.orthoflow.voice.infrastructure.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is unique to driving a general model as a transcriber: the request
 * shape the Interactions API accepts, pulling both transcripts out of a
 * step-shaped response, noticing when the model answered the audio instead of
 * transcribing it, and the order models are tried in when one is overloaded.
 *
 * <p>The HTTP call itself is not exercised here — it is a single well-trodden
 * {@code HttpClient.send}, and a test that mocks it only asserts that Mockito
 * works. The request body is, because a field in the wrong place is not a
 * degraded transcript but an HTTP 400 on every clip — which is exactly how
 * this client failed before.
 */
class GeminiTranscriptionClientTest {

    private SpeechToTextProperties properties;
    private GeminiTranscriptionClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new SpeechToTextProperties();
        client = new GeminiTranscriptionClient(properties, objectMapper);
    }

    /** A response whose single model_output step carries {@code text}. */
    private String response(String text) throws Exception {
        return objectMapper.writeValueAsString(Map.of("steps", List.of(
                Map.of("type", "model_output", "content", List.of(Map.of("type", "text", "text", text))))));
    }

    // ── Response parsing ────────────────────────────────────────────────

    @Test
    void readsTheTranscriptOutOfTheModelOutputStep() throws Exception {
        String json = """
                {"steps":[{"type":"model_output","content":[
                  {"type":"text","text":"dent seize, carie récurrente"}]}]}
                """;
        assertThat(GeminiTranscriptionClient.extractText(objectMapper.readTree(json)))
                .isEqualTo("dent seize, carie récurrente");
    }

    @Test
    void skipsThinkingStepsRatherThanConcatenatingThemIntoTheTranscript() throws Exception {
        // A thought step carries the model's reasoning. Concatenated into the
        // transcript it would reach the grammar, and from there a clinical record.
        String json = """
                {"steps":[
                  {"type":"thought","signature":"EvEFCu4FAQw"},
                  {"type":"model_output","content":[{"type":"text","text":"dent dix-sept, couronne à refaire"}]}]}
                """;
        assertThat(GeminiTranscriptionClient.extractText(objectMapper.readTree(json)))
                .isEqualTo("dent dix-sept, couronne à refaire");
    }

    @Test
    void anEmptyOrUnexpectedResponseYieldsBlankRatherThanThrowing() throws Exception {
        assertThat(GeminiTranscriptionClient.extractText(objectMapper.readTree("{}"))).isEmpty();
        assertThat(GeminiTranscriptionClient.extractText(objectMapper.readTree("{\"steps\":[]}"))).isEmpty();
        assertThat(GeminiTranscriptionClient.extractText(objectMapper.readTree("{\"steps\":[{\"type\":\"thought\"}]}")))
                .isEmpty();
    }

    @Test
    void readsBothTranscriptsFromTheStructuredResponse() throws Exception {
        String body = response("""
                {"speech":true,"text":"Calypso, dent seize, carie récurente",
                 "normalized":"Calypso, dent 16, carie récurrente","language":"fr"}""");

        TranscriptionResult result = client.parse(body, "en", "gemini-3.8-flash");

        assertThat(result.error()).isNull();
        assertThat(result.text()).isEqualTo("Calypso, dent seize, carie récurente");
        assertThat(result.normalized()).isEqualTo("Calypso, dent 16, carie récurrente");
        // The detected language wins over the interface-language hint.
        assertThat(result.language()).isEqualTo("fr");
        assertThat(result.model()).isEqualTo("gemini-3.8-flash");
    }

    @Test
    void aClipWithNoSpeechIsAnEmptyTranscriptNotAnError() throws Exception {
        String body = response("{\"speech\":false,\"text\":\"\",\"normalized\":\"\",\"language\":\"\"}");

        TranscriptionResult result = client.parse(body, "fr", "gemini-3.8-flash");

        assertThat(result.error()).isNull();
        assertThat(result.hasText()).isFalse();
        assertThat(result.normalized()).isNull();
    }

    @Test
    void acceptsABareTranscriptWhenTheModelIgnoresTheFormat() throws Exception {
        TranscriptionResult result = client.parse(response("dent 16, carie"), "fr", "gemini-3.5-flash");

        assertThat(result.text()).isEqualTo("dent 16, carie");
        assertThat(result.normalized()).isNull();
    }

    @Test
    void acceptsAStructuredResponseWrappedInAMarkdownFence() throws Exception {
        String body = response("```json\n{\"speech\":true,\"text\":\"annule\",\"normalized\":\"annule\",\"language\":\"fr\"}\n```");

        assertThat(client.parse(body, "fr", "m").text()).isEqualTo("annule");
    }

    @Test
    void treatsAModelRefusalAsAFailedTranscriptionInBothLanguages() throws Exception {
        assertThat(GeminiTranscriptionClient.looksLikeRefusal("I cannot help with that request.")).isTrue();
        assertThat(GeminiTranscriptionClient.looksLikeRefusal("Je ne peux pas transcrire cet audio.")).isTrue();
        assertThat(GeminiTranscriptionClient.looksLikeRefusal("As an AI, I don't have the ability to hear.")).isTrue();

        String structured = response("{\"speech\":true,\"text\":\"I'm sorry, I cannot transcribe this.\","
                + "\"normalized\":\"\",\"language\":\"en\"}");
        assertThat(client.parse(structured, "en", "m").error()).isEqualTo("stt-non-transcript-response");
    }

    @Test
    void doesNotMistakeOrdinarySpeechForARefusal() {
        // A dentist saying this into the microphone is dictation, not a refusal.
        assertThat(GeminiTranscriptionClient.looksLikeRefusal(
                "le patient dit qu'il ne peut pas ouvrir la bouche complètement")).isFalse();
        assertThat(GeminiTranscriptionClient.looksLikeRefusal("dent seize, carie récurrente")).isFalse();
        assertThat(GeminiTranscriptionClient.looksLikeRefusal("")).isFalse();
    }

    @Test
    void aLongTranscriptIsNeverTreatedAsARefusal() {
        // Refusals are short. A real transcript that happens to open with an
        // apology the patient made must not be discarded.
        String longUtterance = "I'm sorry " + "carie récurrente sur la seize, ".repeat(20);
        assertThat(longUtterance.length()).isGreaterThan(200);
        assertThat(GeminiTranscriptionClient.looksLikeRefusal(longUtterance)).isFalse();
    }

    // ── Request shape ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void sendsThinkingLevelAndSchemaWhereTheInteractionsApiAcceptsThem() {
        Map<String, Object> body = client.requestBody("gemini-3.8-flash", "AAAA", "audio/wav", "fr", null);

        // Both were once sent at the top level; the API rejects them there.
        assertThat(body).doesNotContainKeys("thinking_level", "output_config", "max_output_tokens");

        Map<String, Object> generation = (Map<String, Object>) body.get("generation_config");
        assertThat(generation).containsEntry("thinking_level", "low");

        Map<String, Object> format = (Map<String, Object>) body.get("response_format");
        assertThat(format).containsEntry("type", "text").containsEntry("mime_type", "application/json");
        assertThat((Map<String, Object>) format.get("schema")).containsKey("properties");

        assertThat(body).containsEntry("store", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void givesTheFallbackModelItsOwnThinkingLevel() {
        // gemini-3.8-flash rejects "minimal"; gemini-3.5-flash is fastest with it.
        properties.setGeminiModel("gemini-3.8-flash");
        properties.setGeminiFallbackModel("gemini-3.5-flash");

        Map<String, Object> primary =
                (Map<String, Object>) client.requestBody("gemini-3.8-flash", "AAAA", "audio/wav", null, null).get("generation_config");
        Map<String, Object> fallback =
                (Map<String, Object>) client.requestBody("gemini-3.5-flash", "AAAA", "audio/wav", null, null).get("generation_config");

        assertThat(primary).containsEntry("thinking_level", "low");
        assertThat(fallback).containsEntry("thinking_level", "minimal");
    }

    @Test
    @SuppressWarnings("unchecked")
    void omitsTheThinkingLevelWhenItIsBlank() {
        properties.setGeminiThinkingLevel("");

        Map<String, Object> generation =
                (Map<String, Object>) client.requestBody("m", "AAAA", "audio/wav", null, null).get("generation_config");

        assertThat(generation).doesNotContainKey("thinking_level");
    }

    @Test
    void passesTheLanguageAsAHintAndTheBrowserVocabularyVerbatim() {
        String instruction = client.userInstruction("en", "Calypso; dent 16; carie récurrente");

        assertThat(instruction).contains("hint only").contains("Calypso; dent 16; carie récurrente");
        assertThat(instruction).doesNotContain(GeminiTranscriptionClient.DEFAULT_VOCABULARY);
    }

    @Test
    void fallsBackToTheServerVocabularyAndCapsAnOversizedOne() {
        assertThat(client.userInstruction(null, null)).contains(GeminiTranscriptionClient.DEFAULT_VOCABULARY);

        String huge = "carie; ".repeat(2000);
        String instruction = client.userInstruction(null, huge);
        assertThat(instruction.length()).isLessThan(GeminiTranscriptionClient.MAX_HINT_CHARS + 300);
    }

    // ── Overload fallback ───────────────────────────────────────────────

    @Test
    void triesThePrimaryFirstAndTheFallbackSecond() {
        properties.setGeminiModel("gemini-3.8-flash");
        properties.setGeminiFallbackModel("gemini-3.5-flash");

        assertThat(client.modelsToTry(1_000)).containsExactly("gemini-3.8-flash", "gemini-3.5-flash");
    }

    @Test
    void prefersTheFallbackWhileAnOverloadedPrimaryCoolsDown() {
        properties.setGeminiModel("gemini-3.8-flash");
        properties.setGeminiFallbackModel("gemini-3.5-flash");
        client.primaryCooldownUntil = 5_000;

        assertThat(client.modelsToTry(1_000)).containsExactly("gemini-3.5-flash", "gemini-3.8-flash");
        assertThat(client.modelsToTry(6_000)).containsExactly("gemini-3.8-flash", "gemini-3.5-flash");
    }

    @Test
    void aBlankOrIdenticalFallbackMeansOneModelOnly() {
        properties.setGeminiModel("gemini-3.8-flash");
        properties.setGeminiFallbackModel("");
        assertThat(client.modelsToTry(0)).containsExactly("gemini-3.8-flash");

        properties.setGeminiFallbackModel("gemini-3.8-flash");
        assertThat(client.modelsToTry(0)).containsExactly("gemini-3.8-flash");
    }

    // ── MIME type ───────────────────────────────────────────────────────

    @Test
    void stripsCodecParametersMediaRecorderAddsToTheMimeType() {
        // MediaRecorder reports audio/webm;codecs=opus, which the API rejects.
        assertThat(GeminiTranscriptionClient.mimeType("audio/webm;codecs=opus", "audio.webm")).isEqualTo("audio/webm");
        assertThat(GeminiTranscriptionClient.mimeType("audio/ogg; codecs=vorbis", "audio.ogg")).isEqualTo("audio/ogg");
        assertThat(GeminiTranscriptionClient.mimeType("audio/x-wav", "utterance.wav")).isEqualTo("audio/wav");
    }

    @Test
    void fallsBackToTheFilenameThenToWavForAnUnknownContentType() {
        assertThat(GeminiTranscriptionClient.mimeType("application/octet-stream", "clip.webm")).isEqualTo("audio/webm");
        assertThat(GeminiTranscriptionClient.mimeType(null, "clip.m4a")).isEqualTo("audio/m4a");
        assertThat(GeminiTranscriptionClient.mimeType(null, null)).isEqualTo("audio/wav");
    }

    // ── Configuration ───────────────────────────────────────────────────

    @Test
    void isNotConfiguredWithoutAnApiKey() {
        SpeechToTextProperties unconfigured = new SpeechToTextProperties();
        assertThat(new GeminiTranscriptionClient(unconfigured, objectMapper).isConfigured()).isFalse();

        unconfigured.setApiKey("test-key");
        assertThat(new GeminiTranscriptionClient(unconfigured, objectMapper).isConfigured()).isTrue();
    }

    @Test
    void reportsAnUnconfiguredProviderInsteadOfAttemptingTheCall() {
        TranscriptionResult result =
                client.transcribe(new byte[] {1, 2, 3}, "audio.wav", "audio/wav", null, null);
        assertThat(result.error()).isEqualTo("stt-not-configured");
        assertThat(result.hasText()).isFalse();
    }
}
