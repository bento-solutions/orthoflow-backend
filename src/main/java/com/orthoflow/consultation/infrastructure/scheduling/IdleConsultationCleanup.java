package com.orthoflow.consultation.infrastructure.scheduling;

import com.orthoflow.consultation.application.service.ConsultationService;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;

/**
 * Discards consultations nobody finished. A doctor who closes the tab and never
 * comes back leaves a whole conversation on the server; production keeps no
 * transcript, so it must not stay there forever.
 *
 * <p>Runs whether or not the feature is switched on: what was recorded while it
 * was on is cleaned up after it is turned off. Each consultation is discarded
 * in its own transaction, so one that fails does not keep the others.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class IdleConsultationCleanup {

    private final ConsultationRepository consultations;
    private final ConsultationService consultationService;
    private final ConsultationExtractionProperties properties;

    @Scheduled(cron = "${orthoflow.consultation.idle-cleanup-cron:0 23 * * * *}",
            zone = "${orthoflow.consultation.timezone:Africa/Casablanca}")
    public void run() {
        OffsetDateTime before = OffsetDateTime.now().minusHours(properties.getIdleDiscardHours());
        for (Consultation idle : consultations.findIdleOpen(before)) {
            try {
                if (consultationService.discardIdle(idle.getId())) {
                    log.info("Discarded consultation {}: nothing happened on it for {} h",
                            idle.getId(), properties.getIdleDiscardHours());
                } else {
                    log.warn("Consultation {} was idle for {} h but a save already wrote chart findings; "
                            + "erased its transcript and left it for the doctor to save",
                            idle.getId(), properties.getIdleDiscardHours());
                }
            } catch (RuntimeException e) {
                log.error("Could not discard idle consultation {}", idle.getId(), e);
            }
        }
    }
}
