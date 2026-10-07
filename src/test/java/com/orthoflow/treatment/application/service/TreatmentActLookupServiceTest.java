package com.orthoflow.treatment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TreatmentActLookupServiceTest {

    private final TreatmentRepository repository = mock(TreatmentRepository.class);
    private final TreatmentActLookupService lookup = new TreatmentActLookupService(repository);

    @Test
    void returnsTheInsurersCodeAndCoefficientForCodesThatNameATreatment() {
        Treatment bracket = new Treatment();
        bracket.setActCode("TO 90");
        bracket.setActCoefficient(new BigDecimal("90"));
        when(repository.findByCode("ORTHO-01")).thenReturn(Optional.of(bracket));
        when(repository.findByCode("OTHER")).thenReturn(Optional.empty());

        var found = lookup.byTreatmentCodes(Arrays.asList("ORTHO-01", "OTHER", null, " ", "ORTHO-01"));

        assertThat(found).containsOnlyKeys("ORTHO-01");
        assertThat(found.get("ORTHO-01").actCode()).isEqualTo("TO 90");
        assertThat(found.get("ORTHO-01").coefficient()).isEqualByComparingTo("90");
        // Asked once for a code however many lines carry it.
        verify(repository, times(1)).findByCode("ORTHO-01");
    }
}
