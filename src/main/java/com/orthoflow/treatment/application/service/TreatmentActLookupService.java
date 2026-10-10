package com.orthoflow.treatment.application.service;

import com.orthoflow.treatment.application.port.TreatmentActLookup;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TreatmentActLookupService implements TreatmentActLookup {

    private final TreatmentRepository treatments;
    private final NgapNomenclature nomenclature;

    @Override
    @Transactional(readOnly = true)
    public Map<String, Act> byTreatmentCodes(Collection<String> treatmentCodes) {
        Map<String, Act> found = new LinkedHashMap<>();
        for (String code : treatmentCodes) {
            if (code == null || code.isBlank() || found.containsKey(code)) continue;
            treatments.findByCode(code).ifPresent(t -> found.put(code, new Act(t.getActCode(), t.getActCoefficient(),
                    nomenclature.find(t.getActCode()).map(a -> a.cotation(t.getActCoefficient())).orElse(null))));
        }
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, CodedTreatment> byTreatmentIds(Collection<UUID> treatmentIds) {
        List<Treatment> rows = treatmentIds.stream().filter(Objects::nonNull).distinct()
                .map(treatments::findById).flatMap(Optional::stream).toList();
        Map<String, NgapNomenclature.NgapAct> acts = nomenclature.byCodes(rows.stream().map(Treatment::getActCode).toList());
        Map<UUID, CodedTreatment> found = new LinkedHashMap<>();
        for (Treatment t : rows) {
            NgapNomenclature.NgapAct act = t.getActCode() == null ? null : acts.get(NgapNomenclature.normalise(t.getActCode()));
            found.put(t.getId(), new CodedTreatment(t.getId(), t.getName(), t.getActCode(), t.getActCoefficient(),
                    act == null ? null : act.cotation(t.getActCoefficient()), act != null && act.priorAgreement()));
        }
        return found;
    }
}
