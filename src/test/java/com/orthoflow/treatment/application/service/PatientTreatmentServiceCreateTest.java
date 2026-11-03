package com.orthoflow.treatment.application.service;

import com.orthoflow.inventory.application.port.ConsumableLedger;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.treatment.application.dto.PatientTreatmentConsumableRequest;
import com.orthoflow.treatment.application.dto.PatientTreatmentRequest;
import com.orthoflow.treatment.domain.model.PatientTreatment;
import com.orthoflow.treatment.domain.model.PatientTreatmentStatus;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.repository.PatientTreatmentRepository;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A treatment can be recorded as already done. The stock it used is moved against its id,
 * which only exists once it is saved; deducting first failed with a null id.
 */
class PatientTreatmentServiceCreateTest {

    private final PatientTreatmentRepository treatments = mock(PatientTreatmentRepository.class);
    private final PatientLookup patients = mock(PatientLookup.class);
    private final TreatmentRepository catalogue = mock(TreatmentRepository.class);
    private final StockItemRepository stock = mock(StockItemRepository.class);
    private final ConsumableLedger ledger = mock(ConsumableLedger.class);
    private final PatientTreatmentService service = new PatientTreatmentService(treatments, patients, catalogue, stock, ledger, mock(PractitionerService.class));

    private final UUID patientId = UUID.randomUUID();
    private final UUID treatmentId = UUID.randomUUID();
    private final UUID stockItemId = UUID.randomUUID();

    private PatientTreatmentRequest request(PatientTreatmentStatus status) {
        when(patients.exists(patientId)).thenReturn(true);
        when(catalogue.findById(treatmentId)).thenReturn(Optional.of(Treatment.builder().name("Composite").build()));
        StockItem item = StockItem.builder().id(stockItemId).name("Composite resin").currentStock(BigDecimal.TEN).build();
        when(stock.findById(stockItemId)).thenReturn(Optional.of(item));
        // What persisting does: @PrePersist gives the entity its id.
        when(treatments.save(any(PatientTreatment.class))).thenAnswer(call -> {
            PatientTreatment saved = call.getArgument(0);
            saved.prePersist();
            return saved;
        });

        PatientTreatmentConsumableRequest line = new PatientTreatmentConsumableRequest();
        line.setStockItemId(stockItemId);
        line.setQuantityUsed(BigDecimal.ONE);
        PatientTreatmentRequest request = new PatientTreatmentRequest();
        request.setTreatmentId(treatmentId);
        request.setTeeth("16");
        request.setStatus(status);
        request.setProgress(status == PatientTreatmentStatus.COMPLETED ? 100 : 0);
        request.setConsumables(List.of(line));
        return request;
    }

    @Test
    void aTreatmentRecordedAsDoneIsSavedAndUsesItsStock() {
        PatientTreatment saved = service.createPatientTreatment(patientId, request(PatientTreatmentStatus.COMPLETED), UUID.randomUUID());

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isStockMovementsGenerated()).isTrue();
        verify(ledger).consume(eq(stockItemId), eq(BigDecimal.ONE), any(), eq(saved.getId()), any(), any(), any());
    }

    @Test
    void aTreatmentStillToComeUsesNoStock() {
        PatientTreatment saved = service.createPatientTreatment(patientId, request(PatientTreatmentStatus.PLANNED), UUID.randomUUID());

        assertThat(saved.isStockMovementsGenerated()).isFalse();
        verify(ledger, never()).consume(any(), any(), any(), any(), any(), any(), any());
    }
}
