package com.orthoflow.consultation.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ConsultationRetentionGuardTest {

    private static ConsultationRetentionGuard guard(boolean retain, String... profiles) {
        ConsultationExtractionProperties properties = new ConsultationExtractionProperties();
        properties.setRetainTranscript(retain);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new ConsultationRetentionGuard(properties, environment);
    }

    @Test
    void productionWillNotStartWhileTranscriptsWouldBeKept() {
        assertThatThrownBy(() -> guard(true, "prod").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONSULTATION_RETAIN_TRANSCRIPT");
    }

    @Test
    void productionStartsWithRetentionOff() {
        assertThatCode(() -> guard(false, "prod").afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void aTestDeploymentMayKeepThem() {
        assertThatCode(() -> guard(true, "dev").afterPropertiesSet()).doesNotThrowAnyException();
    }
}
