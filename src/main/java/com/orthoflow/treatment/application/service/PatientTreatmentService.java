package com.orthoflow.treatment.application.service;

import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.treatment.application.dto.PatientTreatmentConsumableRequest;
import com.orthoflow.treatment.application.dto.PatientTreatmentRequest;
import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.treatment.domain.model.*;
import com.orthoflow.treatment.domain.repository.PatientTreatmentRepository;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.treatment.domain.repository.TreatmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import com.orthoflow.inventory.application.port.ConsumableLedger;
import com.orthoflow.inventory.domain.model.SourceType;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.treatment.domain.model.PatientTreatment;
import com.orthoflow.treatment.domain.model.PatientTreatmentConsumable;
import com.orthoflow.treatment.domain.model.PatientTreatmentStatus;
import com.orthoflow.treatment.domain.model.Treatment;
import com.orthoflow.treatment.domain.model.TreatmentConsumable;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientTreatmentService {

    private final PatientTreatmentRepository patientTreatmentRepository;
    private final PatientLookup patientLookup;
    private final TreatmentRepository treatmentRepository;
    private final StockItemRepository stockItemRepository;
    private final ConsumableLedger consumableLedger;
    private final PractitionerService practitionerService;

    public List<PatientTreatment> getTreatmentsByPatient(UUID patientId) {
        return patientTreatmentRepository.findByPatientId(patientId);
    }

    public List<PatientTreatment> getAllPatientTreatments() {
        return patientTreatmentRepository.findAll();
    }

    public Optional<PatientTreatment> getTreatmentById(UUID id) {
        return patientTreatmentRepository.findById(id);
    }

    /**
     * Creates a patient treatment from a validated request DTO. Binding the
     * JPA entity directly (the previous behaviour) let a client set
     * `stockMovementsGenerated: true` on create — deductStock() no-ops when
     * that flag is already true, so the treatment was recorded as completed
     * with no inventory movement and no trace in the audit ledger (audit
     * V.6, exploit A). `stockMovementsGenerated` is now computed here, never
     * client-supplied.
     */
    /**
     * The free-text name stays populated for screens that predate practitioners:
     * a chosen practitioner's display name wins over whatever text was sent.
     */
    private String doctorNameFor(PatientTreatmentRequest request) {
        if (request.getPractitionerId() == null) {
            return request.getDoctorName();
        }
        var practitioner = practitionerService.byIds(Set.of(request.getPractitionerId())).get(request.getPractitionerId());
        if (practitioner == null) {
            throw new NotFoundException("Practitioner not found: " + request.getPractitionerId());
        }
        return practitioner.getDisplayName();
    }

    @Transactional
    public PatientTreatment createPatientTreatment(UUID patientId, PatientTreatmentRequest request, UUID createdBy) {
        if (!patientLookup.exists(patientId)) {
            throw new NotFoundException("Patient not found: " + patientId);
        }

        Treatment treatment = treatmentRepository.findById(request.getTreatmentId())
                .orElseThrow(() -> new NotFoundException("Treatment not found: " + request.getTreatmentId()));

        PatientTreatment pt = PatientTreatment.builder()
                .patientId(patientId)
                .treatment(treatment)
                .teeth(request.getTeeth())
                .status(request.getStatus())
                .progress(request.getProgress())
                .notes(request.getNotes())
                .doctorName(doctorNameFor(request))
                .practitionerId(request.getPractitionerId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .consumables(new ArrayList<>())
                .build();

        pt.getConsumables().addAll(buildConsumables(pt, request.getConsumables(), treatment));

        if (pt.getStatus() == PatientTreatmentStatus.COMPLETED) {
            deductStock(pt, createdBy);
        }

        return patientTreatmentRepository.save(pt);
    }

    @Transactional
    public PatientTreatment updatePatientTreatment(UUID id, PatientTreatmentRequest request, UUID actorId) {
        PatientTreatment existing = patientTreatmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Patient Treatment not found: " + id));

        Treatment treatment = treatmentRepository.findById(request.getTreatmentId())
                .orElseThrow(() -> new NotFoundException("Treatment not found: " + request.getTreatmentId()));
        existing.setTreatment(treatment);

        existing.setTeeth(request.getTeeth());
        existing.setProgress(request.getProgress());
        existing.setNotes(request.getNotes());
        existing.setDoctorName(doctorNameFor(request));
        existing.setPractitionerId(request.getPractitionerId());
        existing.setStartDate(request.getStartDate());
        existing.setEndDate(request.getEndDate());

        // Snapshot persisted quantities per stock item before touching the
        // consumables list — needed for the COMPLETED→COMPLETED delta below.
        Map<UUID, BigDecimal> previousQuantities = quantitiesByStockItem(existing.getConsumables());

        existing.getConsumables().clear();
        existing.getConsumables().addAll(buildConsumables(existing, request.getConsumables(), treatment));

        PatientTreatmentStatus oldStatus = existing.getStatus();
        PatientTreatmentStatus newStatus = request.getStatus();
        existing.setStatus(newStatus);

        if (newStatus == PatientTreatmentStatus.COMPLETED && oldStatus != PatientTreatmentStatus.COMPLETED) {
            deductStock(existing, actorId);
        } else if (newStatus != PatientTreatmentStatus.COMPLETED && oldStatus == PatientTreatmentStatus.COMPLETED) {
            restoreStock(existing, actorId);
        } else if (newStatus == PatientTreatmentStatus.COMPLETED) {
            // Editing an already-completed treatment's consumables (e.g.
            // correcting 2 brackets to 10) previously left inventory
            // untouched — deductStock() no-ops once the flag is set, and
            // this branch wasn't handled at all (audit II.5). Reconcile the
            // difference instead of silently letting the record and the
            // ledger disagree.
            reconcileStock(existing, previousQuantities, actorId);
        }

        return patientTreatmentRepository.save(existing);
    }

    @Transactional
    public void deletePatientTreatment(UUID id, UUID actorId) {
        PatientTreatment pt = patientTreatmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Patient Treatment not found: " + id));

        if (pt.isStockMovementsGenerated()) {
            restoreStock(pt, actorId);
        }
        // Archived, not deleted — clinical treatment history carries
        // retention obligations (audit II.15).
        pt.setDeletedAt(java.time.OffsetDateTime.now());
        pt.setDeletedBy(actorId);
        patientTreatmentRepository.save(pt);
    }

    private List<PatientTreatmentConsumable> buildConsumables(
            PatientTreatment pt, List<PatientTreatmentConsumableRequest> requested, Treatment treatment) {

        List<PatientTreatmentConsumable> consumables = new ArrayList<>();
        if (requested == null || requested.isEmpty()) {
            if (treatment.getConsumables() != null) {
                for (TreatmentConsumable tc : treatment.getConsumables()) {
                    consumables.add(PatientTreatmentConsumable.builder()
                            .patientTreatment(pt)
                            .stockItem(tc.getStockItem())
                            .quantityUsed(tc.getQuantityUsed())
                            .pricePerUnit(tc.getStockItem().getPricePerUse())
                            .notes(tc.getNotes())
                            .build());
                }
            }
        } else {
            for (PatientTreatmentConsumableRequest r : requested) {
                StockItem stockItem = stockItemRepository.findById(r.getStockItemId())
                        .orElseThrow(() -> new NotFoundException("Stock item not found: " + r.getStockItemId()));
                consumables.add(PatientTreatmentConsumable.builder()
                        .patientTreatment(pt)
                        .stockItem(stockItem)
                        .quantityUsed(r.getQuantityUsed())
                        .pricePerUnit(stockItem.getPricePerUse())
                        .notes(r.getNotes())
                        .build());
            }
        }
        return consumables;
    }

    private Map<UUID, BigDecimal> quantitiesByStockItem(List<PatientTreatmentConsumable> consumables) {
        return consumables.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getStockItem().getId(),
                        Collectors.reducing(BigDecimal.ZERO, PatientTreatmentConsumable::getQuantityUsed, BigDecimal::add)));
    }

    private void reconcileStock(PatientTreatment pt, Map<UUID, BigDecimal> previousQuantities, UUID actorId) {
        Map<UUID, BigDecimal> newQuantities = quantitiesByStockItem(pt.getConsumables());
        Map<UUID, StockItem> stockItemsById = pt.getConsumables().stream()
                .collect(Collectors.toMap(c -> c.getStockItem().getId(), PatientTreatmentConsumable::getStockItem, (a, b) -> a));

        Set<UUID> allItemIds = new HashSet<>();
        allItemIds.addAll(previousQuantities.keySet());
        allItemIds.addAll(newQuantities.keySet());

        // Validate before applying any movement, same as deductStock: a
        // partially-applied reconciliation would be worse than none.
        for (UUID stockItemId : allItemIds) {
            BigDecimal delta = newQuantities.getOrDefault(stockItemId, BigDecimal.ZERO)
                    .subtract(previousQuantities.getOrDefault(stockItemId, BigDecimal.ZERO));
            if (delta.compareTo(BigDecimal.ZERO) <= 0) continue;
            StockItem stockItem = stockItemsById.get(stockItemId);
            BigDecimal currentStock = stockItem.getCurrentStock() != null ? stockItem.getCurrentStock() : BigDecimal.ZERO;
            if (currentStock.compareTo(delta) < 0) {
                throw new IllegalArgumentException("Insufficient stock for item: " + stockItem.getName() +
                        " (Available: " + currentStock + ", Additional required: " + delta + ")");
            }
        }

        String reference = "PT-" + pt.getId().toString().substring(0, 8).toUpperCase();

        for (UUID stockItemId : allItemIds) {
            BigDecimal before = previousQuantities.getOrDefault(stockItemId, BigDecimal.ZERO);
            BigDecimal after = newQuantities.getOrDefault(stockItemId, BigDecimal.ZERO);
            BigDecimal delta = after.subtract(before);
            if (delta.compareTo(BigDecimal.ZERO) == 0) continue;

            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                consumableLedger.consume(
                        stockItemId, delta, SourceType.PATIENT_TREATMENT, pt.getId(), reference,
                        "Additional quantity deducted after correcting a completed treatment (" + pt.getTreatment().getName() + ")",
                        actorId);
            } else {
                consumableLedger.restore(
                        stockItemId, delta.abs(), SourceType.PATIENT_TREATMENT, pt.getId(), reference,
                        "Quantity restored after correcting a completed treatment (" + pt.getTreatment().getName() + ")",
                        actorId);
            }
        }
    }

    private void deductStock(PatientTreatment pt, UUID actorId) {
        if (pt.isStockMovementsGenerated()) return;

        // Validation first
        if (pt.getConsumables() != null) {
            for (PatientTreatmentConsumable line : pt.getConsumables()) {
                BigDecimal qty = line.getQuantityUsed();
                if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
                    StockItem stockItem = line.getStockItem();

                    // 1. Decimal Validation
                    if (!stockItem.isDecimalSupported()) {
                        if (qty.stripTrailingZeros().scale() > 0) {
                            throw new IllegalArgumentException("Decimal quantity not supported for item: " + stockItem.getName() +
                                    " (Provided: " + qty + "). Only whole numbers are allowed.");
                        }
                    }

                    // 2. Stock Level Validation
                    BigDecimal currentStock = stockItem.getCurrentStock() != null ? stockItem.getCurrentStock() : BigDecimal.ZERO;
                    if (currentStock.compareTo(qty) < 0) {
                        throw new IllegalArgumentException("Insufficient stock for item: " + stockItem.getName() +
                                " (Available: " + currentStock + ", Required: " + qty + ")");
                    }
                }
            }
        }

        // Deduct
        if (pt.getConsumables() != null) {
            for (PatientTreatmentConsumable line : pt.getConsumables()) {
                BigDecimal qty = line.getQuantityUsed();
                if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
                    consumableLedger.consume(
                            line.getStockItem().getId(),
                            qty,
                            SourceType.PATIENT_TREATMENT,
                            pt.getId(),
                            "PT-" + pt.getId().toString().substring(0, 8).toUpperCase(),
                            "Deducted for Patient Treatment (" + pt.getTreatment().getName() + ")",
                            actorId
                    );
                }
            }
            pt.setStockMovementsGenerated(true);
        }
    }

    private void restoreStock(PatientTreatment pt, UUID actorId) {
        if (!pt.isStockMovementsGenerated()) return;

        if (pt.getConsumables() != null) {
            for (PatientTreatmentConsumable line : pt.getConsumables()) {
                BigDecimal qty = line.getQuantityUsed();
                if (qty != null && qty.compareTo(BigDecimal.ZERO) > 0) {
                    consumableLedger.restore(
                            line.getStockItem().getId(),
                            qty,
                            SourceType.PATIENT_TREATMENT,
                            pt.getId(),
                            "PT-" + pt.getId().toString().substring(0, 8).toUpperCase(),
                            "Restored due to Patient Treatment status change from COMPLETED",
                            actorId
                    );
                }
            }
            pt.setStockMovementsGenerated(false);
        }
    }
}
