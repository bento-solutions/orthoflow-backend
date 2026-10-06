package com.orthoflow.settings.infrastructure.adapter;

import com.orthoflow.scheduling.application.port.OpeningHoursProvider;
import com.orthoflow.settings.application.service.PracticeProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OpeningHoursAdapter implements OpeningHoursProvider {

    private final PracticeProfileService profiles;

    @Override
    public Optional<Day> forWeekday(UUID practiceId, int isoWeekday) {
        return Optional.ofNullable(profiles.forWeekday(practiceId, isoWeekday))
                .map(h -> new Day(h.getOpenTime(), h.getCloseTime(), h.getBreakStart(), h.getBreakEnd()));
    }
}
