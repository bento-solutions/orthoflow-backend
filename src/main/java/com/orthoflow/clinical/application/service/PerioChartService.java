package com.orthoflow.clinical.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.clinical.application.dto.PerioChartDtos.Chart;
import com.orthoflow.clinical.application.dto.PerioChartDtos.ChartListItem;
import com.orthoflow.clinical.application.dto.PerioChartDtos.CreateChartRequest;
import com.orthoflow.clinical.application.dto.PerioChartDtos.SaveChartRequest;
import com.orthoflow.clinical.domain.model.PerioChart;
import com.orthoflow.clinical.domain.perio.PerioChartValidator;
import com.orthoflow.clinical.domain.perio.PerioSites;
import com.orthoflow.clinical.domain.perio.PerioSummary;
import com.orthoflow.clinical.domain.perio.ToothMeasurements;
import com.orthoflow.clinical.domain.repository.PerioChartRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.domain.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The periodontal chart: the six-point probing record of each examination, kept so the next one can
 * be compared with it. A chart is read and written whole; every save is checked against the ranges of
 * the examination and against the version the screen was built on.
 */
@Service
@RequiredArgsConstructor
public class PerioChartService {

    private static final TypeReference<Map<String, String>> STATUS = new TypeReference<>() { };
    private static final TypeReference<Map<String, ToothMeasurements>> TEETH = new TypeReference<>() { };

    private final PerioChartRepository charts;
    private final PatientRepository patients;
    private final ObjectMapper json;
    private final ActivityLog activity;

    @Transactional(readOnly = true)
    public List<ChartListItem> list(UUID patientId) {
        requirePatient(patientId);
        return charts.findAlive(patientId).stream().map(this::toListItem).toList();
    }

    @Transactional(readOnly = true)
    public List<ChartListItem> listDeleted(UUID patientId) {
        requirePatient(patientId);
        return charts.findDeleted(patientId).stream().map(this::toListItem).toList();
    }

    /** Every chart that was not deleted, with its measurements: for the patient's right of access. */
    @Transactional(readOnly = true)
    public List<Chart> allCharts(UUID patientId) {
        requirePatient(patientId);
        return charts.findAlive(patientId).stream().map(this::toChart).toList();
    }

    @Transactional(readOnly = true)
    public Chart get(UUID patientId, UUID chartId) {
        requirePatient(patientId);
        return toChart(alive(patientId, chartId));
    }

    @Transactional
    public Chart create(UUID practiceId, UUID actorId, UUID patientId, CreateChartRequest request) {
        requirePatient(patientId);
        LocalDate date = chartDate(request.chartDate(), LocalDate.now());

        // A tooth that was missing last time is still missing: the statuses travel, the measurements do not.
        String carriedStatus = charts.findAlive(patientId).stream().findFirst()
                .map(PerioChart::getToothStatus)
                .orElse("{}");

        PerioChart saved = charts.save(PerioChart.builder()
                .patientId(patientId)
                .chartDate(date)
                .visitReason(blankToNull(request.visitReason()))
                .toothStatus(carriedStatus)
                .teeth("{}")
                .createdBy(actorId)
                .updatedBy(actorId)
                .build());
        activity.record(practiceId, "PATIENT", patientId, "PERIO_CHART_CREATED",
                Map.of("chartId", saved.getId().toString(), "chartDate", date.toString()));
        return toChart(saved);
    }

    @Transactional
    public Chart save(UUID actorId, UUID patientId, UUID chartId, SaveChartRequest request) {
        requirePatient(patientId);
        PerioChart chart = alive(patientId, chartId);
        if (!chart.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException(
                    "This chart was changed on another screen. Reload it to see the current version.");
        }

        Map<String, ToothMeasurements> teeth = canonical(request.teeth());
        Map<String, String> status = sortedStatus(request.toothStatus());
        PerioChartValidator.validate(status, teeth);

        LocalDate date = chartDate(request.chartDate(), chart.getChartDate());
        String reason = blankToNull(request.visitReason());
        String notes = blankToNull(request.clinicalNotes());

        // Autosave sends the whole chart every time. Saving what is already there must not move the
        // version or the "updated" stamp: compare what the chart says, not how its JSON is spelled
        // (jsonb keeps keys in its own order).
        boolean unchanged = date.equals(chart.getChartDate())
                && Objects.equals(reason, chart.getVisitReason())
                && Objects.equals(notes, chart.getClinicalNotes())
                && status.equals(read(chart.getToothStatus(), STATUS))
                && teeth.equals(canonical(read(chart.getTeeth(), TEETH)));
        if (unchanged) {
            return toChart(chart);
        }

        chart.setChartDate(date);
        chart.setVisitReason(reason);
        chart.setClinicalNotes(notes);
        chart.setToothStatus(write(status));
        chart.setTeeth(write(teeth));
        chart.setUpdatedBy(actorId);
        return toChart(charts.save(chart));
    }

    @Transactional
    public void delete(UUID practiceId, UUID patientId, UUID chartId) {
        requirePatient(patientId);
        PerioChart chart = alive(patientId, chartId);
        chart.setDeletedAt(OffsetDateTime.now());
        charts.save(chart);
        activity.record(practiceId, "PATIENT", patientId, "PERIO_CHART_DELETED",
                Map.of("chartId", chartId.toString(), "chartDate", chart.getChartDate().toString()));
    }

    @Transactional
    public Chart restore(UUID practiceId, UUID patientId, UUID chartId) {
        requirePatient(patientId);
        PerioChart chart = charts.findOfPatient(patientId, chartId)
                .orElseThrow(() -> new NotFoundException("Periodontal chart not found: " + chartId));
        if (!chart.isDeleted()) {
            return toChart(chart);
        }
        chart.setDeletedAt(null);
        PerioChart restored = charts.save(chart);
        activity.record(practiceId, "PATIENT", patientId, "PERIO_CHART_RESTORED",
                Map.of("chartId", chartId.toString(), "chartDate", chart.getChartDate().toString()));
        return toChart(restored);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void requirePatient(UUID patientId) {
        if (patients.findById(patientId).isEmpty()) {
            throw new NotFoundException("Patient not found: " + patientId);
        }
    }

    private PerioChart alive(UUID patientId, UUID chartId) {
        return charts.findOfPatient(patientId, chartId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new NotFoundException("Periodontal chart not found: " + chartId));
    }

    private static LocalDate chartDate(LocalDate requested, LocalDate fallback) {
        LocalDate date = requested == null ? fallback : requested;
        if (date.isAfter(LocalDate.now())) {
            throw new ValidationException("The date of a periodontal chart cannot be in the future.");
        }
        if (date.getYear() < 1900) {
            throw new ValidationException("That date is not plausible for a periodontal chart.");
        }
        return date;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Sparse and in chart order: teeth with nothing charted are not stored, whatever the client sent. */
    private static Map<String, ToothMeasurements> canonical(Map<String, ToothMeasurements> sent) {
        Map<String, ToothMeasurements> result = new LinkedHashMap<>();
        for (String tooth : PerioSites.TEETH) {
            ToothMeasurements m = sent.get(tooth);
            if (m != null && !m.isEmpty()) {
                result.put(tooth, m.canonical());
            }
        }
        // Anything else the client sent is an unknown tooth: leave it in so the validator names it.
        for (Map.Entry<String, ToothMeasurements> entry : sent.entrySet()) {
            if (!result.containsKey(entry.getKey()) && !PerioSites.isTooth(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static Map<String, String> sortedStatus(Map<String, String> status) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String tooth : PerioSites.TEETH) {
            if (status.containsKey(tooth)) {
                result.put(tooth, status.get(tooth));
            }
        }
        return result;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the periodontal chart", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored periodontal chart cannot be read", e);
        }
    }

    private ChartListItem toListItem(PerioChart c) {
        return new ChartListItem(c.getId(), c.getChartDate(), c.getVisitReason(), c.getVersion(),
                c.getUpdatedAt(), c.getDeletedAt(), PerioSummary.of(read(c.getTeeth(), TEETH)));
    }

    private Chart toChart(PerioChart c) {
        Map<String, ToothMeasurements> teeth = read(c.getTeeth(), TEETH);
        return new Chart(c.getId(), c.getPatientId(), c.getChartDate(), c.getVisitReason(), c.getClinicalNotes(),
                read(c.getToothStatus(), STATUS), teeth, c.getVersion(), c.getCreatedAt(), c.getUpdatedAt(),
                PerioSummary.of(teeth));
    }
}
