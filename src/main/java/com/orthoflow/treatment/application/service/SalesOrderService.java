package com.orthoflow.treatment.application.service;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.treatment.application.dto.SalesOrderLineRequest;
import com.orthoflow.treatment.application.dto.SalesOrderRequest;
import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.treatment.domain.model.*;
import com.orthoflow.treatment.domain.repository.SalesOrderRepository;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.orthoflow.inventory.application.port.ConsumableLedger;
import com.orthoflow.inventory.domain.model.SourceType;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.treatment.domain.model.SOStatus;
import com.orthoflow.treatment.domain.model.SalesOrder;
import com.orthoflow.treatment.domain.model.SalesOrderLine;

@Service
@RequiredArgsConstructor
public class SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final PatientLookup patientLookup;
    private final StockItemRepository stockItemRepository;
    private final ConsumableLedger consumableLedger;

    public List<SalesOrder> getAllSalesOrders() {
        return enrichWithPatients(salesOrderRepository.findAll());
    }

    public Optional<SalesOrder> getSalesOrderById(UUID id) {
        return salesOrderRepository.findById(id).map(this::enrichWithPatient);
    }

    public Optional<SalesOrder> getSalesOrderByNumber(String soNumber) {
        return salesOrderRepository.findBySoNumber(soNumber).map(this::enrichWithPatient);
    }

    /** Attaches the display-only PatientSummary this entity no longer carries as a JPA relation. */
    private SalesOrder enrichWithPatient(SalesOrder order) {
        order.setPatient(patientLookup.findSummary(order.getPatientId()).orElse(null));
        return order;
    }

    private List<SalesOrder> enrichWithPatients(List<SalesOrder> orders) {
        List<UUID> patientIds = orders.stream().map(SalesOrder::getPatientId).distinct().toList();
        Map<UUID, PatientSummary> summaries = patientIds.isEmpty()
                ? Collections.emptyMap()
                : patientLookup.findSummaries(patientIds);
        orders.forEach(o -> o.setPatient(summaries.get(o.getPatientId())));
        return orders;
    }

    /**
     * Creates a new DRAFT sales order from a validated request DTO.
     * `totalAmount`/`status`/`soNumber` are always server-owned — binding
     * the entity directly (the previous behaviour) let a client set
     * `totalAmount` or `status` outright (see audit I.5 / V.6).
     */
    @Transactional
    public SalesOrder createSalesOrder(SalesOrderRequest request) {
        UUID patientId = request.getPatient().getId();
        PatientSummary patient = patientLookup.findSummary(patientId)
                .orElseThrow(() -> new NotFoundException("Patient not found: " + patientId));

        SalesOrder order = SalesOrder.builder()
                .patientId(patientId)
                .status(SOStatus.DRAFT)
                .notes(request.getNotes())
                .build();
        order.setSoNumber(generateSoNumber());

        for (SalesOrderLineRequest lineRequest : request.getLines()) {
            StockItem stockItem = stockItemRepository.findById(lineRequest.getStockItem().getId())
                    .orElseThrow(() -> new NotFoundException("Stock item not found: " + lineRequest.getStockItem().getId()));
            BigDecimal unitPrice = lineRequest.getUnitPrice() != null
                    ? lineRequest.getUnitPrice()
                    : stockItem.getPurchasePrice(); // fallback, matches prior behaviour
            SalesOrderLine line = SalesOrderLine.builder()
                    .stockItem(stockItem)
                    .quantity(lineRequest.getQuantity())
                    .unitPrice(unitPrice)
                    .discount(lineRequest.getDiscount())
                    .build();
            order.addLine(line);
        }

        order.calculateTotal();
        SalesOrder saved = salesOrderRepository.save(order);
        saved.setPatient(patient);
        return saved;
    }

    @Transactional
    public SalesOrder confirmSalesOrder(UUID id, UUID confirmedBy) {
        SalesOrder order = enrichWithPatient(salesOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sales order not found: " + id)));

        if (order.getStatus() != SOStatus.DRAFT) {
            throw new ConflictException("Only DRAFT sales orders can be confirmed.");
        }

        order.setStatus(SOStatus.CONFIRMED);

        // Deduct stock immediately
        if (order.getLines() != null) {
            for (SalesOrderLine line : order.getLines()) {
                consumableLedger.consume(
                        line.getStockItem().getId(),
                        line.getQuantity(),
                        SourceType.SALES_ORDER,
                        order.getId(),
                        order.getSoNumber(),
                        "Patient direct purchase: " + order.getSoNumber(),
                        confirmedBy
                );
            }
        }

        return salesOrderRepository.save(order);
    }

    @Transactional
    public SalesOrder cancelSalesOrder(UUID id, UUID cancelledBy) {
        SalesOrder order = enrichWithPatient(salesOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sales order not found: " + id)));

        if (order.getStatus() == SOStatus.CANCELLED) {
            throw new ConflictException("Sales order is already cancelled.");
        }

        // Restore stock if it was previously confirmed
        if (order.getStatus() == SOStatus.CONFIRMED && order.getLines() != null) {
            for (SalesOrderLine line : order.getLines()) {
                consumableLedger.restore(
                        line.getStockItem().getId(),
                        line.getQuantity(),
                        SourceType.SALES_ORDER,
                        order.getId(),
                        order.getSoNumber(),
                        "Restored due to Sales Order cancellation: " + order.getSoNumber(),
                        cancelledBy
                );
            }
        }

        order.setStatus(SOStatus.CANCELLED);
        return salesOrderRepository.save(order);
    }

    @Transactional
    public void deleteSalesOrder(UUID id) {
        SalesOrder order = salesOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sales order not found: " + id));
        if (order.getStatus() == SOStatus.CONFIRMED) {
            throw new ConflictException("Confirmed sales orders cannot be deleted.");
        }
        salesOrderRepository.deleteById(id);
    }

    private synchronized String generateSoNumber() {
        int nextVal = salesOrderRepository.findAll().size() + 1;
        return "SO-" + LocalDate.now().getYear() + "-" + String.format("%04d", nextVal);
    }
}
