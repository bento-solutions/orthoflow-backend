package com.orthoflow.sterilization.application.service;

import com.orthoflow.sterilization.domain.model.SterilizationEvent;
import com.orthoflow.sterilization.domain.model.SterilizationEvent.Action;
import com.orthoflow.sterilization.domain.model.SterilizationItem;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import com.orthoflow.sterilization.infrastructure.SterilizationEventJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Writes the sterilization register. The only writer of {@code sterilization_events}. */
@Component
@RequiredArgsConstructor
public class SterilizationRegister {

    private final SterilizationEventJpaRepository events;

    public void record(SterilizationItem item, Action action, State from, UUID actorId, OffsetDateTime at, UUID cycleId, UUID patientId,
                       UUID appointmentId, String note) {
        events.save(SterilizationEvent.builder().practiceId(item.getPracticeId()).itemId(item.getId()).action(action).fromState(from)
                .toState(item.getState()).occurredAt(at).performedBy(actorId).cycleId(cycleId).patientId(patientId)
                .appointmentId(appointmentId).note(note == null || note.isBlank() ? null : note.trim()).build());
    }
}
