package com.orthoflow.consultation.infrastructure.extraction;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a production deployment that would keep consultation
 * transcripts.
 *
 * <p>Keeping the raw conversation exists for testing the consultation feature
 * and goes against production's "no transcript is kept" rule (docs/VOICE.md §9).
 * A flag nobody remembers to leave off is not a guarantee; a backend that will
 * not boot is.
 */
@Component
public class ConsultationRetentionGuard implements InitializingBean {

    private final ConsultationExtractionProperties properties;
    private final Environment environment;

    public ConsultationRetentionGuard(ConsultationExtractionProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.isRetainTranscript() && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("orthoflow.consultation.retain-transcript "
                    + "(CONSULTATION_RETAIN_TRANSCRIPT) is for testing only and cannot be on under the prod profile.");
        }
    }
}
