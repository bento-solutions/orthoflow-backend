package com.orthoflow.procurement.application.service;

import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.procurement.application.dto.VendorInvoiceCreateRequest;
import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.procurement.domain.model.*;
import com.orthoflow.procurement.domain.repository.DeliveryNoteRepository;
import com.orthoflow.procurement.domain.repository.VendorInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.orthoflow.procurement.domain.model.DNStatus;
import com.orthoflow.procurement.domain.model.DeliveryNote;
import com.orthoflow.procurement.domain.model.DeliveryNoteLine;
import com.orthoflow.procurement.domain.model.VendorInvoice;
import com.orthoflow.procurement.domain.model.VendorInvoiceLine;
import com.orthoflow.procurement.domain.model.VendorInvoiceStatus;

/**
 * BR03 — Vendor Invoices. Clears a received delivery note's GRNI balance and
 * creates the vendor liability. Deliberately does NOT touch
 * StockItem.currentStock: goods were already received (and stock already
 * incremented) when the DeliveryNote itself was received — a vendor invoice
 * is a financial/liability event, not an inventory-quantity event.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorInvoiceService {

    private final VendorInvoiceRepository vendorInvoiceRepository;
    private final List<com.orthoflow.procurement.application.port.VendorInvoiceListener> listeners;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentNumbers documentNumbers;

    public List<VendorInvoice> getAllVendorInvoices() {
        return vendorInvoiceRepository.findAll();
    }

    public Optional<VendorInvoice> getVendorInvoiceById(UUID id) {
        return vendorInvoiceRepository.findById(id);
    }

    @Transactional
    public VendorInvoice createVendorInvoice(VendorInvoiceCreateRequest request, UUID createdBy) {
        DeliveryNote deliveryNote = deliveryNoteRepository.findById(request.getDeliveryNoteId())
                .orElseThrow(() -> new NotFoundException("Delivery note not found: " + request.getDeliveryNoteId()));

        if (deliveryNote.getStatus() != DNStatus.RECEIVED) {
            throw new IllegalArgumentException(
                    "Cannot vendor-invoice delivery note " + deliveryNote.getDnNumber() +
                            " — it has not been received yet (status: " + deliveryNote.getStatus() + ")");
        }

        if (!vendorInvoiceRepository.findActiveByDeliveryNoteId(deliveryNote.getId()).isEmpty()) {
            throw new ConflictException(
                    "Delivery note " + deliveryNote.getDnNumber() + " has already been vendor-invoiced");
        }

        VendorInvoice invoice = VendorInvoice.builder()
                .vendorInvoiceNumber(request.getInvoiceNumber() != null && !request.getInvoiceNumber().isBlank()
                        ? request.getInvoiceNumber() : generateVendorInvoiceNumber())
                .deliveryNote(deliveryNote)
                .supplier(deliveryNote.getPurchaseOrder().getSupplier())
                .status(VendorInvoiceStatus.DRAFT)
                .invoiceDate(request.getInvoiceDate() != null ? request.getInvoiceDate() : LocalDate.now())
                .invoiceAmount(request.getInvoiceAmount())
                .paymentTerms(request.getPaymentTerms())
                .notes(request.getNotes())
                .createdBy(createdBy)
                .build();

        for (DeliveryNoteLine dnLine : deliveryNote.getLines()) {
            VendorInvoiceLine line = VendorInvoiceLine.builder()
                    .stockItem(dnLine.getStockItem())
                    .quantityInvoiced(dnLine.getQuantityReceived())
                    .unitPrice(dnLine.getPoLine().getUnitPrice())
                    .build();
            line.calculateLineTotal();
            invoice.addLine(line);
        }

        return vendorInvoiceRepository.save(invoice);
    }

    @Transactional
    public VendorInvoice validateVendorInvoice(UUID id, UUID validatedBy) {
        VendorInvoice invoice = vendorInvoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Vendor invoice not found: " + id));

        if (invoice.getStatus() != VendorInvoiceStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT vendor invoices can be validated.");
        }

        invoice.setStatus(VendorInvoiceStatus.VALIDATED);
        invoice.setValidatedAt(OffsetDateTime.now());
        invoice.setValidatedBy(validatedBy);
        VendorInvoice saved = vendorInvoiceRepository.save(invoice);
        // Validating is what makes it a cost to the clinic: finance books the expense.
        var summary = new com.orthoflow.procurement.application.port.VendorInvoiceListener.Summary(saved.getId(),
                saved.getPracticeId(), saved.getVendorInvoiceNumber(), saved.getSupplier().getName(), saved.getInvoiceDate(),
                saved.getInvoiceAmount(), saved.getPaymentTerms(), validatedBy);
        listeners.forEach(l -> l.onValidated(summary));
        return saved;
    }

    @Transactional
    public VendorInvoice cancelVendorInvoice(UUID id) {
        VendorInvoice invoice = vendorInvoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Vendor invoice not found: " + id));

        if (invoice.getStatus() == VendorInvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Vendor invoice is already cancelled.");
        }

        invoice.setStatus(VendorInvoiceStatus.CANCELLED);
        VendorInvoice saved = vendorInvoiceRepository.save(invoice);
        listeners.forEach(l -> l.onCancelled(saved.getId()));
        return saved;
    }

    @Transactional
    public void deleteVendorInvoice(UUID id) {
        VendorInvoice invoice = vendorInvoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Vendor invoice not found: " + id));

        if (invoice.getStatus() != VendorInvoiceStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT vendor invoices can be deleted.");
        }

        vendorInvoiceRepository.deleteById(id);
    }

    private String generateVendorInvoiceNumber() {
        Long nextVal = documentNumbers.next("vendor_invoice");
        return "VI-" + LocalDate.now().getYear() + "-" + String.format("%04d", nextVal);
    }
}
