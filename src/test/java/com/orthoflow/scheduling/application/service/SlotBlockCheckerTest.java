package com.orthoflow.scheduling.application.service;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.scheduling.domain.model.AbsenceReason;
import com.orthoflow.scheduling.domain.model.CalendarEvent;
import com.orthoflow.scheduling.domain.model.PractitionerAbsence;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.CalendarEventJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.PractitionerAbsenceJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlotBlockCheckerTest {

    private final UUID practice = UUID.randomUUID();
    private final UUID doctor = UUID.randomUUID();
    private final UUID chair = UUID.randomUUID();
    private final OffsetDateTime start = OffsetDateTime.parse("2027-05-04T10:00:00+01:00");

    private PractitionerAbsenceJpaRepository absences;
    private CalendarEventJpaRepository events;
    private SlotBlockChecker checker;

    @BeforeEach
    void setUp() {
        absences = mock(PractitionerAbsenceJpaRepository.class);
        events = mock(CalendarEventJpaRepository.class);
        when(absences.overlappingFor(any(), any(), any())).thenReturn(List.of());
        when(events.blocking(any(), any(), any(), any(), any())).thenReturn(List.of());
        checker = new SlotBlockChecker(absences, events);
    }

    @Test
    void aFreeSlotIsFree() {
        assertThatCode(() -> checker.assertFree(practice, doctor, chair, start, start.plusMinutes(30))).doesNotThrowAnyException();
    }

    @Test
    void anAbsenceBlocksAndSaysWhy() {
        when(absences.overlappingFor(any(), any(), any())).thenReturn(List.of(
                PractitionerAbsence.builder().reason(AbsenceReason.CONFERENCE).build()));

        assertThatThrownBy(() -> checker.assertFree(practice, doctor, chair, start, start.plusMinutes(30)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("absent").hasMessageContaining("conference");
    }

    @Test
    void anEventBlocksAndIsNamed() {
        when(events.blocking(any(), any(), any(), any(), any())).thenReturn(List.of(
                CalendarEvent.builder().title("Réunion d'équipe").build()));

        assertThatThrownBy(() -> checker.assertFree(practice, doctor, chair, start, start.plusMinutes(30)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Réunion d'équipe");
    }

    @Test
    void aBookingWithNoPractitionerIsNotCheckedAgainstAbsences() {
        checker.assertFree(practice, null, chair, start, start.plusMinutes(30));

        org.mockito.Mockito.verifyNoInteractions(absences);
        assertThat(true).isTrue();
    }
}
