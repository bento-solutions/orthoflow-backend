package com.orthoflow.procurement.application.service;

import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.procurement.application.dto.PurchaseOrderLineRequest;
import com.orthoflow.procurement.application.dto.PurchaseOrderRequest;
import com.orthoflow.procurement.domain.model.POStatus;
import com.orthoflow.procurement.domain.model.PurchaseOrder;
import com.orthoflow.procurement.domain.model.PurchaseOrderLine;
import com.orthoflow.inventory.domain.model.StockItem;
import com.orthoflow.inventory.domain.model.Supplier;
import com.orthoflow.procurement.domain.repository.PurchaseOrderRepository;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import com.orthoflow.inventory.domain.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final StockItemRepository stockItemRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentNumbers documentNumbers;

    public List<PurchaseOrder> getAllPurchaseOrders() {
        return purchaseOrderRepository.findAll();
    }

    public Optional<PurchaseOrder> getPurchaseOrderById(UUID id) {
        return purchaseOrderRepository.findById(id);
    }

    public Optional<PurchaseOrder> getPurchaseOrderByNumber(String poNumber) {
        return purchaseOrderRepository.findByPoNumber(poNumber);
    }

    /**
     * Creates a new DRAFT purchase order from a validated request DTO.
     * `totalAmount`/`status`/`poNumber`/`createdBy` are always server-owned
     * — binding the entity directly (the previous behaviour) let a client
     * set `totalAmount` or `status` outright, and `createdBy` was never set
     * at all despite the column being NOT NULL (see audit I.5 / V.6).
     */
    @Transactional
    public PurchaseOrder createPurchaseOrder(PurchaseOrderRequest request, UUID createdBy) {
        Supplier supplier = supplierRepository.findById(request.getSupplier().getId())
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + request.getSupplier().getId()));

        PurchaseOrder order = PurchaseOrder.builder()
                .supplier(supplier)
                .status(POStatus.DRAFT)
                .orderDate(request.getOrderDate() != null ? request.getOrderDate() : LocalDate.now())
                .expectedDeliveryDate(request.getExpectedDeliveryDate())
                .notes(request.getNotes())
                .createdBy(Objects.requireNonNull(createdBy, "createdBy is required for a purchase order"))
                .build();
        order.setPoNumber(generatePoNumber());

        for (PurchaseOrderLineRequest lineRequest : request.getLines()) {
            StockItem stockItem = stockItemRepository.findById(lineRequest.getStockItem().getId())
                    .orElseThrow(() -> new NotFoundException("Stock item not found: " + lineRequest.getStockItem().getId()));
            PurchaseOrderLine line = PurchaseOrderLine.builder()
                    .stockItem(stockItem)
                    .quantityOrdered(lineRequest.getQuantityOrdered())
                    .unitPrice(lineRequest.getUnitPrice())
                    .quantityReceived(BigDecimal.ZERO)
                    .build();
            line.calculateTotalPrice();
            order.addLine(line);
        }

        order.calculateTotal();
        return purchaseOrderRepository.save(order);
    }

    /**
     * Full edit of a DRAFT purchase order — lines, supplier, dates, notes.
     * Mirrors what createPurchaseOrder accepts. Only DRAFT POs can be edited;
     * once sent/received/etc. the document is treated as issued.
     */
    @Transactional
    public PurchaseOrder updatePurchaseOrder(UUID id, PurchaseOrderRequest update) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Purchase order not found: " + id));

        if (order.getStatus() != POStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only a DRAFT purchase order can be edited (current status: " + order.getStatus() + ")");
        }

        if (update.getSupplier() != null && update.getSupplier().getId() != null) {
            Supplier supplier = supplierRepository.findById(update.getSupplier().getId())
                    .orElseThrow(() -> new NotFoundException("Supplier not found: " + update.getSupplier().getId()));
            order.setSupplier(supplier);
        }

        if (update.getExpectedDeliveryDate() != null) {
            order.setExpectedDeliveryDate(update.getExpectedDeliveryDate());
        }
        order.setNotes(update.getNotes());

        order.getLines().clear();
        for (PurchaseOrderLineRequest lineRequest : update.getLines()) {
            StockItem stockItem = stockItemRepository.findById(lineRequest.getStockItem().getId())
                    .orElseThrow(() -> new NotFoundException("Stock item not found: " + lineRequest.getStockItem().getId()));
            PurchaseOrderLine line = PurchaseOrderLine.builder()
                    .stockItem(stockItem)
                    .quantityOrdered(lineRequest.getQuantityOrdered())
                    .unitPrice(lineRequest.getUnitPrice())
                    .quantityReceived(BigDecimal.ZERO)
                    .build();
            line.calculateTotalPrice();
            order.addLine(line);
        }

        order.calculateTotal();
        return purchaseOrderRepository.save(order);
    }

    @Transactional
    public PurchaseOrder updatePurchaseOrderStatus(UUID id, POStatus status) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Purchase order not found: " + id));
        order.setStatus(status);
        return purchaseOrderRepository.save(order);
    }

    /** Semantic alias for the frontend's explicit "confirm" action: DRAFT → SENT. */
    @Transactional
    public PurchaseOrder confirmPurchaseOrder(UUID id) {
        return updatePurchaseOrderStatus(id, POStatus.SENT);
    }

    @Transactional
    public void deletePurchaseOrder(UUID id) {
        purchaseOrderRepository.deleteById(id);
    }

    private String generatePoNumber() {
        Long nextVal = documentNumbers.next("purchase_order");
        return "PO-" + LocalDate.now().getYear() + "-" + String.format("%04d", nextVal);
    }
}
