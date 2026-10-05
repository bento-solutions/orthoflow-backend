package com.orthoflow.settings.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.settings.application.dto.PracticeProfileDtos.*;
import com.orthoflow.settings.domain.model.OpeningHours;
import com.orthoflow.settings.domain.model.PracticeProfile;
import com.orthoflow.settings.domain.model.PracticeSettings;
import com.orthoflow.settings.infrastructure.adapter.persistence.OpeningHoursJpaRepository;
import com.orthoflow.settings.infrastructure.adapter.persistence.PracticeProfileJpaRepository;
import com.orthoflow.settings.infrastructure.adapter.persistence.PracticeSettingsJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PracticeProfileService {

    private final PracticeProfileJpaRepository profiles;
    private final OpeningHoursJpaRepository hours;
    private final PracticeSettingsJpaRepository settings;

    @Transactional(readOnly = true)
    public Profile get(UUID practiceId) {
        return Profile.from(require(practiceId));
    }

    @Transactional
    public Profile update(UUID practiceId, Profile request) {
        PracticeProfile p = require(practiceId);
        p.setName(request.name().trim());
        p.setLegalName(blankToNull(request.legalName()));
        p.setIce(blankToNull(request.ice()));
        p.setTaxId(blankToNull(request.taxId()));
        p.setPatente(blankToNull(request.patente()));
        p.setCnssNumber(blankToNull(request.cnssNumber()));
        p.setRib(blankToNull(request.rib()));
        p.setInpe(blankToNull(request.inpe()));
        p.setAddress(blankToNull(request.address()));
        p.setCity(blankToNull(request.city()));
        p.setPhone(blankToNull(request.phone()));
        p.setEmail(blankToNull(request.email()));
        p.setWebsite(blankToNull(request.website()));
        if (request.currency() != null) p.setCurrency(request.currency());
        if (request.timezone() != null) p.setTimezone(request.timezone());
        if (request.defaultLanguage() != null) p.setDefaultLanguage(request.defaultLanguage());
        return Profile.from(profiles.save(p));
    }

    @Transactional
    public void setLogo(UUID practiceId, UUID fileId) {
        PracticeProfile p = require(practiceId);
        p.setLogoFileId(fileId);
        profiles.save(p);
    }

    @Transactional(readOnly = true)
    public PracticeProfile require(UUID practiceId) {
        return profiles.findById(practiceId).orElseThrow(() -> new NotFoundException("Practice not found"));
    }

    /** Always seven days, Monday first; a day that was never configured is returned as the default. */
    @Transactional
    public Week openingHours(UUID practiceId) {
        Map<Short, OpeningHours> byDay = hours.findByPracticeIdOrderByWeekdayAsc(practiceId).stream()
                .collect(Collectors.toMap(OpeningHours::getWeekday, Function.identity()));
        for (short day = 1; day <= 7; day++) {
            if (!byDay.containsKey(day)) {
                byDay.put(day, hours.save(OpeningHours.builder().practiceId(practiceId).weekday(day)
                        .closed(day == 7).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(19, 0)).build()));
            }
        }
        return new Week(byDay.values().stream().sorted(Comparator.comparingInt(OpeningHours::getWeekday))
                .map(Day::from).toList());
    }

    @Transactional
    public Week replaceOpeningHours(UUID practiceId, Week week) {
        Set<Short> seen = new HashSet<>();
        for (Day d : week.days()) {
            if (!seen.add(d.weekday())) {
                throw new ValidationException("Weekday " + d.weekday() + " appears twice");
            }
        }
        openingHours(practiceId);
        Map<Short, OpeningHours> byDay = hours.findByPracticeIdOrderByWeekdayAsc(practiceId).stream()
                .collect(Collectors.toMap(OpeningHours::getWeekday, Function.identity()));
        for (Day d : week.days()) {
            OpeningHours row = byDay.get(d.weekday());
            row.setClosed(d.closed());
            row.setOpenTime(d.openTime());
            row.setCloseTime(d.closeTime());
            row.setBreakStart(d.breakStart());
            row.setBreakEnd(d.breakEnd());
        }
        hours.saveAll(byDay.values());
        syncWorkingHours(practiceId, byDay.values().stream().filter(h -> !h.isClosed()).toList());
        return openingHours(practiceId);
    }

    /** Free time slots for a weekday come from here; null when the clinic is closed that day. */
    @Transactional(readOnly = true)
    public OpeningHours forWeekday(UUID practiceId, int isoWeekday) {
        return hours.findByPracticeIdOrderByWeekdayAsc(practiceId).stream()
                .filter(h -> h.getWeekday() == isoWeekday && !h.isClosed())
                .findFirst().orElse(null);
    }

    /**
     * The older clinic-wide start/end hours stay in step (earliest opening,
     * latest closing across open days) so the calendar views that only know
     * those two numbers keep rendering the right range.
     */
    private void syncWorkingHours(UUID practiceId, List<OpeningHours> openDays) {
        if (openDays.isEmpty()) {
            return;
        }
        short start = (short) openDays.stream().mapToInt(h -> h.getOpenTime().getHour()).min().orElse(8);
        short end = (short) openDays.stream()
                .mapToInt(h -> h.getCloseTime().getMinute() > 0 ? h.getCloseTime().getHour() + 1 : h.getCloseTime().getHour())
                .max().orElse(19);
        if (end <= start) {
            return;
        }
        settings.findAll().stream().filter(s -> practiceId.equals(s.getPracticeId())).findFirst().ifPresent(s -> {
            s.setWorkingHoursStart(start);
            s.setWorkingHoursEnd(end);
            settings.save(s);
        });
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
