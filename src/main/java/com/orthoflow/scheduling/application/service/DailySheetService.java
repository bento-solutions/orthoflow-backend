package com.orthoflow.scheduling.application.service;

import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.dto.TableExport.Column;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import com.orthoflow.team.application.service.PractitionerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The printable list of a day's patients, per practitioner or for the whole clinic. */
@Service
@RequiredArgsConstructor
public class DailySheetService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Map<String, List<String>> HEADERS = Map.of(
            "fr", List.of("Heure", "Patient", "Téléphone", "Type", "Durée (min)", "Fauteuil", "Praticien", "Notes"),
            "en", List.of("Time", "Patient", "Phone", "Type", "Duration (min)", "Chair", "Practitioner", "Notes"),
            "ar", List.of("الوقت", "المريض", "الهاتف", "النوع", "المدة (د)", "الكرسي", "الطبيب", "ملاحظات"));
    private static final Map<String, String> TITLE = Map.of("fr", "Planning du ", "en", "Schedule for ", "ar", "جدول يوم ");
    private static final Map<String, String> ALL = Map.of("fr", "Tous les praticiens", "en", "All practitioners", "ar", "جميع الأطباء");

    private final AppointmentService appointmentService;
    private final PractitionerService practitionerService;
    private final PracticeZone practiceZone;

    public TableExport build(UUID practiceId, LocalDate date, UUID practitionerId, String lang) {
        String language = HEADERS.containsKey(lang) ? lang : "fr";
        ZoneId zone = practiceZone.of(practiceId);
        OffsetDateTime from = date.atStartOfDay(zone).toOffsetDateTime();
        List<AppointmentResponse> day = appointmentService.agenda(practiceId, from, from.plusDays(1), practitionerId, null, null, null)
                .stream().filter(a -> a.getStatus() != AppointmentStatus.CANCELLED).toList();
        String who = practitionerId == null ? ALL.get(language)
                : practitionerService.require(practiceId, practitionerId).getDisplayName();
        List<String> h = HEADERS.get(language);
        List<Column> columns = List.of(Column.text(h.get(0)), Column.text(h.get(1)), Column.text(h.get(2)), Column.text(h.get(3)),
                Column.number(h.get(4)), Column.text(h.get(5)), Column.text(h.get(6)), Column.text(h.get(7)));
        List<List<Object>> rows = day.stream().map(a -> List.<Object>of(
                TIME.format(a.getDateTime().atZoneSameInstant(zone)), nz(a.getPatientName()), nz(a.getPatientPhone()), nz(a.getType()),
                a.getDurationMinutes(), nz(a.getChairName()), nz(a.getPractitionerName()), nz(a.getNotes()))).toList();
        return new TableExport(TITLE.get(language) + DAY.format(date), who, columns, rows);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
