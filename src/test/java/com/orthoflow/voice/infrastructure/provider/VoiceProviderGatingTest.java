package com.orthoflow.voice.infrastructure.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A vendor that has not been attested as data-protected must never be handed patient audio or text.
 * Every stage gets its key through {@link VoiceProviderProperties}, so the gate lives there.
 */
class VoiceProviderGatingTest {

    private VoiceProviderProperties vendors;

    @BeforeEach
    void keyedVendors() {
        vendors = new VoiceProviderProperties();
        vendors.getGroq().setApiKey("groq-key");
        vendors.getDeepseek().setApiKey("ds-key");
        vendors.getAssemblyai().setApiKey("aai-key");
    }

    @Test
    void enforcementIsOnByDefault() {
        assertThat(vendors.isRequireDataProtection()).isTrue();
    }

    @Test
    void aKeyedVendorWithoutTheAttestationHasNoUsableKey() {
        assertThat(vendors.keyFor("groq", "assemblyai", "")).isEmpty();
        assertThat(vendors.keyFor("deepseek", "assemblyai", "")).isEmpty();
        assertThat(vendors.getGroq().hasKey()).isFalse();
        assertThat(vendors.getGroq().hasConfiguredKey()).isTrue();
    }

    @Test
    void anAttestedVendorWorks() {
        vendors.getGroq().setDataProtected(true);

        assertThat(vendors.keyFor("groq", "assemblyai", "")).isEqualTo("groq-key");
        assertThat(vendors.keyFor("deepseek", "assemblyai", "")).isEmpty();
    }

    @Test
    void theLegacyStageKeyCannotRouteAroundTheAttestation() {
        // A deployment that only ever set the stage's own key, with that vendor as the stage's primary.
        assertThat(vendors.keyFor("gemini", "gemini", "stage-key")).isEmpty();

        vendors.getGemini().setDataProtected(true);
        assertThat(vendors.keyFor("gemini", "gemini", "stage-key")).isEqualTo("stage-key");
    }

    @Test
    void aVendorOutsideTheAllowedRegionsIsBlockedEvenWhenAttested() {
        vendors.getDeepseek().setDataProtected(true);
        vendors.getDeepseek().setRegion("CN");
        vendors.getAssemblyai().setDataProtected(true);
        vendors.getAssemblyai().setRegion("EU");
        vendors.setAllowedRegions(List.of("EU", "MA"));

        assertThat(vendors.keyFor("deepseek", "assemblyai", "")).isEmpty();
        assertThat(vendors.keyFor("assemblyai", "assemblyai", "")).isEqualTo("aai-key");
    }

    @Test
    void whenRegionsAreRestrictedAVendorThatDeclaresNoneIsBlocked() {
        vendors.getGroq().setDataProtected(true);
        vendors.setAllowedRegions(List.of("EU"));

        assertThat(vendors.keyFor("groq", "assemblyai", "")).isEmpty();
    }

    @Test
    void noRegionListMeansNoRegionRestriction() {
        vendors.getGroq().setDataProtected(true);

        assertThat(vendors.getGroq().isBlocked()).isFalse();
    }

    @Test
    void switchedOffEverythingWorksAsBefore() {
        vendors.setRequireDataProtection(false);

        assertThat(vendors.keyFor("deepseek", "assemblyai", "")).isEqualTo("ds-key");
        assertThat(vendors.getGroq().hasKey()).isTrue();
    }
}
