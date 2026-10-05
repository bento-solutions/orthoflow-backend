package com.orthoflow.scheduling.application.service;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.CalendarEventJpaRepository;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.PractitionerAbsenceJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Absences and calendar events block slots. A booking onto one is refused with
 * the reason, so the receptionist can decide; the caller may override with
 * {@code ignoreBlocks} once they have been told. The database's exclusion
 * constraints deal with double-booking a chair or a practitioner; this deals
 * with time that is closed for another reason.
 */
@Component
@RequiredArgsConstructor
public class SlotBlockChecker {

    private final PractitionerAbsenceJpaRepository absences;
    private final CalendarEventJpaRepository events;

    @Transactional(readOnly = true)
    public void assertFree(UUID practiceId, UUID practitionerId, UUID chairId, OffsetDateTime start, OffsetDateTime end) {
        List<String> reasons = new ArrayList<>();
        if (practitionerId != null) {
            absences.overlappingFor(practitionerId, start, end)
                    .forEach(a -> reasons.add("the practitioner is absent (" + a.getReason().name().toLowerCase() + ")"));
        }
        events.blocking(practiceId, start, end, chairId, practitionerId)
                .forEach(e -> reasons.add("\"" + e.getTitle() + "\" is scheduled"));
        if (!reasons.isEmpty()) {
            throw new ConflictException("This slot is blocked: " + String.join("; ", reasons) + ".");
        }
    }
}
