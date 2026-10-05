package com.orthoflow.recall.application.service;

import com.orthoflow.export.application.dto.TableExport;
import com.orthoflow.export.application.dto.TableExport.Column;
import com.orthoflow.recall.application.dto.RecallDtos.Filter;
import com.orthoflow.recall.application.dto.RecallDtos.Kind;
import com.orthoflow.recall.application.dto.RecallDtos.Row;
import com.orthoflow.recall.infrastructure.RecallQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RecallService {

    private static final Map<Kind, String> TITLES = Map.of(
            Kind.NO_VISIT_1M, "Patients sans visite depuis 1 mois", Kind.NO_VISIT_3M, "Patients sans visite depuis 3 mois",
            Kind.NO_VISIT_6M, "Patients sans visite depuis 6 mois", Kind.NOTHING_SCHEDULED_1M, "Aucun rendez-vous dans le mois à venir",
            Kind.NOTHING_SCHEDULED_12M, "Aucun rendez-vous dans l'année à venir", Kind.LOST_TO_FOLLOW_UP, "Traitements actifs sans prochain rendez-vous",
            Kind.RETENTION_DUE_6M, "Contrôle de contention à 6 mois", Kind.RETENTION_DUE_12M, "Contrôle de contention à 12 mois");

    private final RecallQuery query;

    @Transactional(readOnly = true)
    public List<Row> list(Filter filter) {
        return query.run(filter, OffsetDateTime.now());
    }

    public TableExport table(Filter filter, List<Row> rows) {
        return new TableExport(TITLES.get(filter.kind()), null,
                List.of(Column.text("Code"), Column.text("Patient"), Column.text("Téléphone"), Column.text("Dernière visite"),
                        Column.text("Praticien"), Column.number("Avancement %"), Column.number("Reste %")),
                rows.stream().map(r -> List.<Object>of(nz(r.patientCode()), r.lastName().toUpperCase() + " " + r.firstName(),
                        nz(r.phone()), r.lastVisit() == null ? "" : r.lastVisit(), nz(r.primaryPractitionerName()),
                        r.progress(), r.remaining())).toList());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
