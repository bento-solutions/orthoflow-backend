package com.orthoflow.procurement.application.service;

import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.procurement.application.dto.DeliveryNoteLineRequest;
import com.orthoflow.procurement.application.dto.DeliveryNoteRequest;
import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.procurement.domain.model.*;
import com.orthoflow.procurement.domain.repository.DeliveryNoteRepository;
import com.orthoflow.procurement.domain.repository.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.orthoflow.inventory.application.port.ConsumableLedger;
import com.orthoflow.inventory.domain.model.SourceType;
import com.orthoflow.procurement.domain.model.DNStatus;
import com.orthoflow.procurement.domain.model.DeliveryNote;
import com.orthoflow.procurement.domain.model.DeliveryNoteLine;
import com.orthoflow.procurement.domain.model.POStatus;
import com.orthoflow.procurement.domain.model.PurchaseOrder;
import com.orthoflow.procurement.domain.model.PurchaseOrderLine;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryNoteService {

    private final DeliveryNoteRepository deliveryNoteRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ConsumableLedger consumableLedger;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentNumbers documentNumbers;

    public List<DeliveryNote> getAllDeliveryNotes() {
        return deliveryNoteRepository.findAll();
    }

    public Optional<DeliveryNote> getDeliveryNoteById(UUID id) {
        return deliveryNoteRepository.findById(id);
    }

    public Optional<DeliveryNote> getDeliveryNoteByNumber(String dnNumber) {
        return deliveryNoteRepository.findByDnNumber(dnNumber);
    }

    /** RECEIVED delivery notes with no non-cancelled vendor invoice referencing them yet (BR03 GRNI). */
    public List<DeliveryNote> getOpenGrni() {
        return deliveryNoteRepository.findOpenGrni();
    }

    /**
     * Creates a new PENDING delivery note from a validated request DTO.
     * `dnNumber`/`status`/`supplier`/`stockMovementsGenerated` are always
     * server-owned — binding the entity directly (the previous behaviour)
     * let a client supply its own `dnNumber`, bypassing the `dn_seq`
     * sequence entirely (see audit I.5 / V.6). `supplier` and each line's
     * `stockItem` are still derived from the purchase order / PO line
     * server-side, exactly as before.
     */
    @Transactional
    public DeliveryNote createDeliveryNote(DeliveryNoteRequest request) {
        PurchaseOrder po = purchaseOrderRepository.findById(request.getPurchaseOrder().getId())
                .orElseThrow(() -> new NotFoundException("Purchase order not found: " + request.getPurchaseOrder().getId()));

        DeliveryNote note = DeliveryNote.builder()
                .purchaseOrder(po)
                .supplier(po.getSupplier())
                .status(DNStatus.PENDING)
                .notes(request.getNotes())
                .build();
        note.setDnNumber(request.getDnNumber() != null && !request.getDnNumber().isBlank()
                ? request.getDnNumber().trim()
                : generateDnNumber());

        for (DeliveryNoteLineRequest lineRequest : request.getLines()) {
            PurchaseOrderLine poLine = po.getLines().stream()
                    .filter(l -> l.getId().equals(lineRequest.getPoLine().getId()))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("PO line not found on parent order: " + lineRequest.getPoLine().getId()));

            DeliveryNoteLine line = DeliveryNoteLine.builder()
                    .poLine(poLine)
                    .stockItem(poLine.getStockItem())
                    .quantityExpected(lineRequest.getQuantityExpected())
                    .quantityReceived(lineRequest.getQuantityReceived())
                    .batchNumber(lineRequest.getBatchNumber())
                    .expiryDate(lineRequest.getExpiryDate())
                    .build();
            note.addLine(line);
        }

        return deliveryNoteRepository.save(note);
    }

    @Transactional
    public DeliveryNote receiveDeliveryNote(UUID id, UUID receivedBy, String notes) {
        DeliveryNote note = deliveryNoteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Delivery note not found: " + id));

        if (note.getStatus() == DNStatus.RECEIVED) {
            throw new IllegalStateException("Delivery note has already been received.");
        }

        note.setStatus(DNStatus.RECEIVED);
        note.setReceivedDate(LocalDate.now());
        note.setReceivedBy(receivedBy);
        if (notes != null && !notes.isEmpty()) {
            note.setNotes(notes);
        }

        // Generate stock movements for each line
        if (!note.isStockMovementsGenerated()) {
            PurchaseOrder po = note.getPurchaseOrder();

            // Validate before applying anything: receiving more than was
            // ordered used to succeed silently and inflate inventory, with
            // the PO then reporting RECEIVED once received >= ordered
            // (audit II.10).
            for (DeliveryNoteLine line : note.getLines()) {
                BigDecimal qtyReceived = line.getQuantityReceived();
                if (qtyReceived == null || qtyReceived.compareTo(BigDecimal.ZERO) <= 0) continue;
                PurchaseOrderLine poLine = line.getPoLine();
                BigDecimal alreadyReceived = poLine.getQuantityReceived() != null ? poLine.getQuantityReceived() : BigDecimal.ZERO;
                BigDecimal totalAfter = alreadyReceived.add(qtyReceived);
                if (totalAfter.compareTo(poLine.getQuantityOrdered()) > 0) {
                    throw new IllegalArgumentException(
                            "Cannot receive " + qtyReceived + " of item " + line.getStockItem().getName() +
                                    " — only " + poLine.getQuantityOrdered().subtract(alreadyReceived) + " remains on the purchase order.");
                }
            }

            for (DeliveryNoteLine line : note.getLines()) {
                BigDecimal qtyReceived = line.getQuantityReceived();
                if (qtyReceived != null && qtyReceived.compareTo(BigDecimal.ZERO) > 0) {
                    // Update Stock Item and record movement
                    consumableLedger.receive(
                            line.getStockItem().getId(),
                            qtyReceived,
                            SourceType.DELIVERY_NOTE,
                            note.getId(),
                            note.getDnNumber(),
                            "Received from Delivery Note " + note.getDnNumber(),
                            receivedBy
                    );

                    // Update quantity received on PO line
                    PurchaseOrderLine poLine = line.getPoLine();
                    BigDecimal currentPoReceived = poLine.getQuantityReceived() != null ? poLine.getQuantityReceived() : BigDecimal.ZERO;
                    poLine.setQuantityReceived(currentPoReceived.add(qtyReceived));
                }
            }

            note.setStockMovementsGenerated(true);

            // Re-evaluate Purchase Order Status
            boolean allFullyReceived = true;
            boolean anyReceived = false;

            for (PurchaseOrderLine poLine : po.getLines()) {
                BigDecimal ordered = poLine.getQuantityOrdered();
                BigDecimal received = poLine.getQuantityReceived() != null ? poLine.getQuantityReceived() : BigDecimal.ZERO;

                if (received.compareTo(BigDecimal.ZERO) > 0) {
                    anyReceived = true;
                }
                if (received.compareTo(ordered) < 0) {
                    allFullyReceived = false;
                }
            }

            if (allFullyReceived) {
                po.setStatus(POStatus.RECEIVED);
            } else if (anyReceived) {
                po.setStatus(POStatus.PARTIAL);
            }
            purchaseOrderRepository.save(po);
        }

        return deliveryNoteRepository.save(note);
    }

    @Transactional
    public void deleteDeliveryNote(UUID id) {
        deliveryNoteRepository.deleteById(id);
    }

    private String generateDnNumber() {
        Long nextVal = documentNumbers.next("delivery_note");
        return "DN-" + LocalDate.now().getYear() + "-" + String.format("%04d", nextVal);
    }
}
