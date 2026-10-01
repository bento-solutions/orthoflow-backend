package com.orthoflow.consultation.infrastructure.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orthoflow.consultation.application.service.ConsultationService;
import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationExtractionProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IdleConsultationCleanupTest {

    private final ConsultationRepository repo = mock(ConsultationRepository.class);
    private final ConsultationService service = mock(ConsultationService.class);
    private final ConsultationExtractionProperties properties = new ConsultationExtractionProperties();

    private static Consultation withId(UUID id) {
        return Consultation.builder().id(id).build();
    }

    @Test
    void discardsEveryConsultationIdleForLongerThanTheLimit() {
        properties.setIdleDiscardHours(24);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repo.findIdleOpen(any())).thenReturn(List.of(withId(a), withId(b)));

        new IdleConsultationCleanup(repo, service, properties).run();

        ArgumentCaptor<OffsetDateTime> before = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).findIdleOpen(before.capture());
        Duration ago = Duration.between(before.getValue(), OffsetDateTime.now());
        org.assertj.core.api.Assertions.assertThat(ago).isBetween(Duration.ofHours(24), Duration.ofHours(24).plusMinutes(1));
        verify(service).discardIdle(a);
        verify(service).discardIdle(b);
    }

    @Test
    void oneThatFailsDoesNotStopTheOthers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repo.findIdleOpen(any())).thenReturn(List.of(withId(a), withId(b)));
        when(service.discardIdle(a)).thenThrow(new IllegalStateException("boom"));

        new IdleConsultationCleanup(repo, service, properties).run();

        verify(service).discardIdle(b);
    }

    @Test
    void nothingIdleNothingDone() {
        when(repo.findIdleOpen(any())).thenReturn(List.of());

        new IdleConsultationCleanup(repo, service, properties).run();

        verify(service, never()).discardIdle(any());
    }
}
