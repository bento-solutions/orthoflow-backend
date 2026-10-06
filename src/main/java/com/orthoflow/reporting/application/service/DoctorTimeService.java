package com.orthoflow.reporting.application.service;

import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.*;
import com.orthoflow.reporting.infrastructure.ReportingQuery;
import com.orthoflow.reporting.infrastructure.ReportingQuery.VisitRow;
import com.orthoflow.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * How long each doctor actually spends in the chair, from the timestamps the front
 * desk records as a visit moves from seated to finished. Active time is seated to
 * finished; waiting is arrived to seated.
 *
 * <p>Timestamps are typed by tired people on a busy day, so every figure is
 * accompanied by the count of visits that were left out and why: a missing end
 * time, an end before the start, a visit shorter or longer than is plausible. A
 * clinic that stamps nothing sees a very large "not recorded" number rather than
 * a confident-looking average of three visits.
 */
@Service
@RequiredArgsConstructor
public class DoctorTimeService {

    static final int DEFAULT_MIN_MINUTES = 5;
    static final int DEFAULT_MAX_MINUTES = 240;
    private static final int MAX_ISSUES = 100;
    private static final double MAX_PLAUSIBLE_WAIT_MINUTES = 8 * 60;

    private final ReportingQuery query;
    private final PracticeZone practiceZone;

    @Transactional(readOnly = true)
    public DoctorTime analyse(UUID practiceId, LocalDate from, LocalDate to, UUID practitionerId, Integer minMinutes, Integer maxMinutes, String lang) {
        ProcedureActivityService.checkRange(from, to);
        int min = minMinutes == null ? DEFAULT_MIN_MINUTES : minMinutes;
        int max = maxMinutes == null ? DEFAULT_MAX_MINUTES : maxMinutes;
        if (min < 0 || max <= min) {
            throw new ValidationException("The plausible range must start at zero or more and end after it starts");
        }
        ZoneId zone = practiceZone.of(practiceId);
        List<VisitRow> visits = query.visits(practiceId, from.atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), practitionerId, lang == null ? "fr" : lang);
        OffsetDateTime startOfToday = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();

        List<Sample> samples = new ArrayList<>();
        List<TimeIssue> issues = new ArrayList<>();
        long missingEnd = 0, missingStart = 0, tooShort = 0, tooLong = 0, invalidOrder = 0, untimed = 0, considered = 0;
        for (VisitRow v : visits) {
            if (v.seatedAt() == null && v.finishedAt() == null) {
                if ("COMPLETED".equals(v.status())) {
                    untimed++;
                }
                continue;
            }
            considered++;
            if (v.seatedAt() != null && v.finishedAt() == null) {
                // Still in the chair is not a problem; a past visit that never ended is.
                if (v.dateTime().isBefore(startOfToday)) {
                    missingEnd++;
                    issues.add(issue(v, "MISSING_END", null));
                }
                continue;
            }
            if (v.seatedAt() == null) {
                missingStart++;
                issues.add(issue(v, "MISSING_START", null));
                continue;
            }
            double minutes = Duration.between(v.seatedAt(), v.finishedAt()).toSeconds() / 60.0;
            if (minutes < 0) {
                invalidOrder++;
                issues.add(issue(v, "END_BEFORE_START", null));
            } else if (minutes < min) {
                tooShort++;
                issues.add(issue(v, "TOO_SHORT", minutes));
            } else if (minutes > max) {
                tooLong++;
                issues.add(issue(v, "TOO_LONG", minutes));
            } else {
                double wait = v.arrivedAt() == null ? -1 : Duration.between(v.arrivedAt(), v.seatedAt()).toSeconds() / 60.0;
                samples.add(new Sample(v, minutes, wait >= 0 && wait <= MAX_PLAUSIBLE_WAIT_MINUTES ? wait : null));
            }
        }
        issues.sort(Comparator.comparing(TimeIssue::when).reversed());
        DataQuality quality = new DataQuality(considered, samples.size(), missingEnd, missingStart, tooShort, tooLong, invalidOrder, untimed,
                issues.stream().limit(MAX_ISSUES).toList());
        return new DoctorTime(from, to, min, max,
                rows(samples, s -> s.visit.practitionerId() + "|", s -> null),
                rows(samples, s -> s.visit.practitionerId() + "|" + s.visit.typeName(), s -> s.visit.typeName()), quality);
    }

    private record Sample(VisitRow visit, double minutes, Double waitMinutes) {
    }

    private static TimeIssue issue(VisitRow v, String problem, Double minutes) {
        return new TimeIssue(v.id(), v.dateTime(), v.practitionerName(), v.typeName(), v.patientCode(), problem,
                minutes == null ? null : BigDecimal.valueOf(minutes).setScale(1, RoundingMode.HALF_UP));
    }

    private static List<TimeRow> rows(List<Sample> samples, java.util.function.Function<Sample, String> key,
                                      java.util.function.Function<Sample, String> type) {
        Map<String, List<Sample>> grouped = new LinkedHashMap<>();
        for (Sample s : samples) {
            grouped.computeIfAbsent(key.apply(s), k -> new ArrayList<>()).add(s);
        }
        List<TimeRow> out = new ArrayList<>();
        for (List<Sample> group : grouped.values()) {
            Sample first = group.get(0);
            double[] minutes = group.stream().mapToDouble(Sample::minutes).sorted().toArray();
            double total = Arrays.stream(minutes).sum();
            double planned = group.stream().mapToInt(s -> s.visit.plannedMinutes()).average().orElse(0);
            OptionalDouble wait = group.stream().filter(s -> s.waitMinutes != null).mapToDouble(s -> s.waitMinutes).average();
            out.add(new TimeRow(first.visit.practitionerId(), first.visit.practitionerName(), type.apply(first), group.size(),
                    one(total), one(total / minutes.length), one(median(minutes)), one(planned), wait.isPresent() ? one(wait.getAsDouble()) : null));
        }
        out.sort(Comparator.comparing((TimeRow r) -> r.practitionerName() == null ? "~" : r.practitionerName())
                .thenComparing(TimeRow::activeMinutes, Comparator.reverseOrder()));
        return out;
    }

    /** The middle value of an already sorted array; the mean of the two middle ones when the count is even. */
    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static BigDecimal one(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }
}
