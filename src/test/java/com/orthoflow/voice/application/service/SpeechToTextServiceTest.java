package com.orthoflow.voice.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.voice.application.dto.TranscriptionResponse;
import com.orthoflow.voice.infrastructure.stt.SpeechToTextProperties;
import com.orthoflow.voice.infrastructure.stt.TranscriptionProvider;
import com.orthoflow.voice.infrastructure.stt.TranscriptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The three things this service must get right: a malformed upload is a hard
 * 400; a switched-off or unreachable provider is a soft "fall back to the
 * browser recogniser" — never an exception, never a silently-empty success
 * that looks like the doctor said nothing; and the provider that runs is the
 * one that was configured, never whichever happens to be first on the
 * classpath.
 */
@ExtendWith(MockitoExtension.class)
class SpeechToTextServiceTest {

    @Mock
    private TranscriptionProvider client;

    @Mock
    private TranscriptionProvider otherProvider;

    private SpeechToTextProperties properties;
    private SpeechToTextService service;
    private long now = 1_000_000;

    @BeforeEach
    void setUp() {
        properties = new SpeechToTextProperties();
        // The default provider name, so the existing cases exercise selection
        // as well as everything they exercised before.
        lenient().when(client.name()).thenReturn("groq");
        lenient().when(otherProvider.name()).thenReturn("gemini");
        lenient().when(client.isConfigured()).thenReturn(true);
        lenient().when(otherProvider.isConfigured()).thenReturn(true);
        lenient().when(client.model()).thenReturn("whisper-large-v3-turbo");
        lenient().when(otherProvider.model()).thenReturn("gemini-3.5-flash");
        service = new SpeechToTextService(properties, List.of(client, otherProvider), () -> now);
    }

    private MockMultipartFile clip(byte[] bytes) {
        return new MockMultipartFile("file", "audio.webm", "audio/webm", bytes);
    }

    @Test
    void rejectsAnEmptyUpload() {
        assertThatThrownBy(() -> service.transcribe(clip(new byte[0]), null, null))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsAClipOverTheConfiguredCeiling() {
        properties.setMaxAudioBytes(8);
        assertThatThrownBy(() -> service.transcribe(clip(new byte[9]), null, null))
                .isInstanceOf(ValidationException.class);
        verifyNoInteractions(client);
    }

    @Test
    void whenDisabledReturnsAnErrorTagAndNeverCallsTheProvider() {
        properties.setEnabled(false);

        TranscriptionResponse response = service.transcribe(clip(new byte[] {1, 2, 3}), null, null);

        assertThat(response.text()).isEmpty();
        assertThat(response.error()).isEqualTo("stt-disabled");
        verify(client, never()).transcribe(any(), any(), any(), any(), any());
    }

    @Test
    void whenEnabledMapsTheProviderTranscriptThrough() {
        properties.setEnabled(true);
        when(client.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofText("upper right first molar, recurrent caries", "french", 3.4));

        TranscriptionResponse response = service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null);

        assertThat(response.text()).isEqualTo("upper right first molar, recurrent caries");
        assertThat(response.error()).isNull();
        assertThat(response.model()).isEqualTo("whisper-large-v3-turbo");
        assertThat(response.language()).isEqualTo("french");
        assertThat(response.durationSeconds()).isEqualTo(3.4);
    }

    @Test
    void callsTheProviderNamedInConfigurationAndNoOther() {
        properties.setEnabled(true);
        properties.setProvider("gemini");
        when(otherProvider.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofText("dent seize, carie récurrente", null, null));

        TranscriptionResponse response = service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null);

        assertThat(response.text()).isEqualTo("dent seize, carie récurrente");
        assertThat(response.provider()).isEqualTo("gemini");
        // The answering provider's model, not whatever the Whisper setting holds.
        assertThat(response.model()).isEqualTo("gemini-3.5-flash");
        verify(client, never()).transcribe(any(), any(), any(), any(), any());
    }

    @Test
    void anUnrecognisedProviderNameFallsBackInsteadOfPickingOneArbitrarily() {
        properties.setEnabled(true);
        // A deployment that meant Gemini and typoed it must get browser
        // recognition and a warning — not audio silently sent to Groq.
        properties.setProvider("gemni");

        TranscriptionResponse response = service.transcribe(clip(new byte[] {1, 2, 3}), null, null);

        assertThat(response.text()).isEmpty();
        assertThat(response.error()).isEqualTo("stt-not-configured");
        verify(client, never()).transcribe(any(), any(), any(), any(), any());
        verify(otherProvider, never()).transcribe(any(), any(), any(), any(), any());
    }

    @Test
    void anUpstreamFailureDegradesInsteadOfThrowing() {
        properties.setEnabled(true);
        properties.setFallbacks(List.of());
        when(client.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofError("stt-http-503"));

        TranscriptionResponse response = service.transcribe(clip(new byte[] {1, 2, 3}), null, null);

        assertThat(response.text()).isEmpty();
        assertThat(response.error()).isEqualTo("stt-http-503");
    }

    // ── The chain ───────────────────────────────────────────────────────

    @Test
    void anOverloadedPrimaryFallsThroughToTheNextProviderWithinTheSameRequest() {
        properties.setEnabled(true);
        properties.setProvider("gemini");
        properties.setFallbacks(List.of("groq"));
        when(otherProvider.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofError("stt-http-429"));
        when(client.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofText("dent 16, carie", "fr", 1.2));

        TranscriptionResponse response = service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null);

        assertThat(response.error()).isNull();
        assertThat(response.text()).isEqualTo("dent 16, carie");
        assertThat(response.provider()).isEqualTo("groq");
    }

    @Test
    void skipsAProviderThatJustReportedOverloadUntilItCoolsDown() {
        properties.setEnabled(true);
        properties.setProvider("gemini");
        properties.setFallbacks(List.of("groq"));
        when(otherProvider.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofError("stt-http-503"));
        when(client.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofText("dent 16, carie", null, null));

        service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null);
        service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null);
        verify(otherProvider, org.mockito.Mockito.times(1)).transcribe(any(), any(), any(), any(), any());

        now += SpeechToTextService.OVERLOAD_COOLDOWN_MS + 1;
        service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null);
        verify(otherProvider, org.mockito.Mockito.times(2)).transcribe(any(), any(), any(), any(), any());
    }

    @Test
    void aPrimaryWithoutAKeyIsSkippedInFavourOfAConfiguredFallback() {
        properties.setEnabled(true);
        properties.setProvider("gemini");
        properties.setFallbacks(List.of("groq"));
        when(otherProvider.isConfigured()).thenReturn(false);
        when(client.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofText("dent 16", null, null));

        assertThat(service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null).provider()).isEqualTo("groq");
        verify(otherProvider, never()).transcribe(any(), any(), any(), any(), any());
    }

    @Test
    void whenEveryProviderFailsTheLastErrorIsReported() {
        properties.setEnabled(true);
        properties.setProvider("gemini");
        properties.setFallbacks(List.of("groq"));
        when(otherProvider.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofError("stt-http-429"));
        when(client.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofError("stt-timeout"));

        TranscriptionResponse response = service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null);

        assertThat(response.text()).isEmpty();
        assertThat(response.error()).isEqualTo("stt-timeout");
    }

    @Test
    void whatRecognisersSayAboutSilenceIsNotACommand() {
        properties.setEnabled(true);
        when(client.transcribe(any(), any(), any(), any(), any()))
                .thenReturn(TranscriptionResult.ofText("Merci.", null, null));

        TranscriptionResponse response = service.transcribe(clip(new byte[] {1, 2, 3}), "fr", null);

        assertThat(response.error()).isNull();
        assertThat(response.text()).isEmpty();
        assertThat(SpeechToTextService.isSilenceHallucination("Sous-titres réalisés par la communauté d'Amara.org"))
                .isTrue();
        // An answer to a confirmation is not noise.
        assertThat(SpeechToTextService.isSilenceHallucination("OK")).isFalse();
        assertThat(SpeechToTextService.isSilenceHallucination("dent 16, carie")).isFalse();
    }
}
