package com.orthoflow.treatment.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.treatment.application.dto.TreatmentConsumableRequest;
import com.orthoflow.treatment.application.dto.TreatmentRequest;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.model.TreatmentConsumable;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TreatmentService {

    private final TreatmentRepository treatmentRepository;
    private final StockItemRepository stockItemRepository;

    public List<Treatment> getAllTreatments() {
        return treatmentRepository.findAll();
    }

    public Optional<Treatment> getTreatmentById(UUID id) {
        return treatmentRepository.findById(id);
    }

    public Optional<Treatment> getTreatmentByCode(String code) {
        return treatmentRepository.findByCode(code);
    }

    /**
     * Creates or updates a treatment catalog entry depending on whether
     * {@code id} is present. `id`/`createdAt`/`updatedAt` are server-owned
     * — binding the entity directly (the previous behaviour) let a client
     * set those and skip validation on `name`/`code`/`basePrice` entirely
     * (see audit I.5 / V.6).
     */
    @Transactional
    public Treatment saveTreatment(TreatmentRequest request, UUID id) {
        Treatment treatment;
        if (id != null) {
            treatment = treatmentRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Treatment not found: " + id));
        } else {
            treatment = new Treatment();
        }

        treatment.setName(request.getName());
        treatment.setCode(request.getCode());
        treatment.setDescription(request.getDescription());
        treatment.setBasePrice(request.getBasePrice());
        treatment.setActive(request.isActive());
        treatment.setCategory(request.getCategory());
        treatment.setDurationMinutes(request.getDurationMinutes());
        treatment.setActCode(request.getActCode() == null || request.getActCode().isBlank() ? null : request.getActCode().trim());
        treatment.setActCoefficient(request.getActCoefficient());

        treatment.getConsumables().clear();
        if (request.getConsumables() != null) {
            for (TreatmentConsumableRequest consumableRequest : request.getConsumables()) {
                StockItem stockItem = stockItemRepository.findById(consumableRequest.getStockItem().getId())
                        .orElseThrow(() -> new NotFoundException("Stock item not found: " + consumableRequest.getStockItem().getId()));
                TreatmentConsumable consumable = TreatmentConsumable.builder()
                        .stockItem(stockItem)
                        .quantityUsed(consumableRequest.getQuantityUsed())
                        .optional(consumableRequest.isOptional())
                        .notes(consumableRequest.getNotes())
                        .build();
                treatment.addConsumable(consumable);
            }
        }

        return treatmentRepository.save(treatment);
    }

    @Transactional
    public void deleteTreatment(UUID id) {
        treatmentRepository.deleteById(id);
    }
}
