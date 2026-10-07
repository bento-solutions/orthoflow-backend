package com.orthoflow.treatment.application.service;

import com.orthoflow.treatment.application.port.TreatmentActLookup;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TreatmentActLookupService implements TreatmentActLookup {

    private final TreatmentRepository treatments;

    @Override
    @Transactional(readOnly = true)
    public Map<String, Act> byTreatmentCodes(Collection<String> treatmentCodes) {
        Map<String, Act> found = new LinkedHashMap<>();
        for (String code : treatmentCodes) {
            if (code == null || code.isBlank() || found.containsKey(code)) continue;
            treatments.findByCode(code).ifPresent(t -> found.put(code, new Act(t.getActCode(), t.getActCoefficient())));
        }
        return found;
    }
}
