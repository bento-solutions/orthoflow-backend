package com.orthoflow.settings.infrastructure.adapter;

import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.settings.application.service.PracticeProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PracticeZoneProvider implements PracticeZone {

    private static final ZoneId FALLBACK = ZoneId.of("Africa/Casablanca");

    private final PracticeProfileService profiles;

    @Override
    public ZoneId of(UUID practiceId) {
        try {
            return ZoneId.of(profiles.require(practiceId).getTimezone());
        } catch (DateTimeException | com.orthoflow.common.exception.NotFoundException e) {
            return FALLBACK;
        }
    }
}
