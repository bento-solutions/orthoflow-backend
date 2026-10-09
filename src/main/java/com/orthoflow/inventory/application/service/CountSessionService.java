package com.orthoflow.inventory.application.service;

import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.inventory.application.dto.CountSessionLineUpdateRequest;
import com.orthoflow.inventory.domain.model.*;
import com.orthoflow.inventory.domain.repository.CountSessionRepository;
import com.orthoflow.inventory.domain.repository.StockItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import com.orthoflow.inventory.domain.model.CountSession;
import com.orthoflow.inventory.domain.model.CountSessionLine;
import com.orthoflow.inventory.domain.model.CountSessionStatus;
import com.orthoflow.inventory.domain.model.MovementType;
import com.orthoflow.inventory.domain.model.SourceType;
import com.orthoflow.inventory.domain.model.StockItem;

/**
 * BR08 — Physical Count Sessions. A session freezes a theoretical-quantity
 * snapshot at creation time (copied, never referenced live), lets counts be
 * entered against that frozen snapshot, and posts signed ADJUSTMENT
 * movements through StockService.recordMovement only on validation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CountSessionService {

    private final CountSessionRepository countSessionRepository;
    private final StockItemRepository stockItemRepository;
    private final StockService stockService;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentNumbers documentNumbers;

    public List<CountSession> getAllCountSessions() {
        return countSessionRepository.findAll();
    }

    public Optional<CountSession> getCountSessionById(UUID id) {
        return countSessionRepository.findById(id);
    }

    @Transactional
    public CountSession createCountSession(String notes, UUID createdBy) {
        CountSession session = CountSession.builder()
                .sessionNumber(generateSessionNumber())
                .status(CountSessionStatus.OPEN)
                .notes(notes)
                .createdBy(createdBy)
                .build();

        List<StockItem> activeItems = stockItemRepository.findAll().stream()
                .filter(StockItem::isActive)
                .toList();

        for (StockItem item : activeItems) {
            CountSessionLine line = CountSessionLine.builder()
                    .stockItem(item)
                    // Copy the value now — this is the whole point of the
                    // freeze: the theoretical quantity must never move again
                    // even if other stock movements happen concurrently.
                    .theoreticalQuantity(item.getCurrentStock() != null ? item.getCurrentStock() : BigDecimal.ZERO)
                    .build();
            session.addLine(line);
        }

        return countSessionRepository.save(session);
    }

    @Transactional
    public CountSession updateCountSessionLines(UUID id, List<CountSessionLineUpdateRequest> updates) {
        CountSession session = countSessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Count session not found: " + id));

        Map<UUID, CountSessionLine> linesByItemId = session.getLines().stream()
                .collect(Collectors.toMap(l -> l.getStockItem().getId(), l -> l));

        for (CountSessionLineUpdateRequest update : updates) {
            CountSessionLine line = linesByItemId.get(update.getStockItemId());
            if (line == null) {
                throw new IllegalArgumentException(
                        "Stock item " + update.getStockItemId() + " is not part of count session " + session.getSessionNumber());
            }
            line.setPhysicalQuantity(update.getPhysicalQuantity());
            line.setNotes(update.getNotes());
            BigDecimal variance = update.getPhysicalQuantity().subtract(line.getTheoreticalQuantity());
            line.setQuantityVariance(variance);
            BigDecimal purchasePrice = line.getStockItem().getPurchasePrice() != null
                    ? line.getStockItem().getPurchasePrice() : BigDecimal.ZERO;
            line.setCostVariance(variance.multiply(purchasePrice));
        }

        if (session.getStatus() == CountSessionStatus.OPEN) {
            session.setStatus(CountSessionStatus.IN_PROGRESS);
        }

        recomputeTotals(session);
        return countSessionRepository.save(session);
    }

    @Transactional
    public CountSession validateCountSession(UUID id, UUID validatedBy) {
        CountSession session = countSessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Count session not found: " + id));

        if (session.getStatus() != CountSessionStatus.OPEN && session.getStatus() != CountSessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("Only an OPEN or IN_PROGRESS count session can be validated.");
        }

        boolean anyCounted = session.getLines().stream().anyMatch(l -> l.getPhysicalQuantity() != null);
        if (!anyCounted) {
            throw new ValidationException("Cannot validate a count session with no physical counts entered.");
        }

        for (CountSessionLine line : session.getLines()) {
            if (line.getPhysicalQuantity() != null && line.getQuantityVariance() != null
                    && line.getQuantityVariance().compareTo(BigDecimal.ZERO) != 0) {
                stockService.recordMovement(
                        line.getStockItem().getId(),
                        MovementType.ADJUSTMENT,
                        line.getQuantityVariance(),
                        SourceType.INVENTORY_COUNT,
                        session.getId(),
                        session.getSessionNumber(),
                        "Physical count adjustment",
                        validatedBy
                );
            }
        }

        recomputeTotals(session);
        session.setStatus(CountSessionStatus.VALIDATED);
        session.setValidatedDate(OffsetDateTime.now());
        session.setValidatedBy(validatedBy);
        return countSessionRepository.save(session);
    }

    @Transactional
    public CountSession cancelCountSession(UUID id) {
        CountSession session = countSessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Count session not found: " + id));

        if (session.getStatus() == CountSessionStatus.VALIDATED) {
            throw new IllegalStateException("A VALIDATED count session cannot be cancelled — its adjustments have already posted.");
        }
        if (session.getStatus() == CountSessionStatus.CANCELLED) {
            throw new IllegalStateException("Count session is already cancelled.");
        }

        session.setStatus(CountSessionStatus.CANCELLED);
        return countSessionRepository.save(session);
    }

    private void recomputeTotals(CountSession session) {
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (CountSessionLine line : session.getLines()) {
            if (line.getQuantityVariance() != null) {
                totalQty = totalQty.add(line.getQuantityVariance());
            }
            if (line.getCostVariance() != null) {
                totalCost = totalCost.add(line.getCostVariance());
            }
        }
        session.setTotalQuantityVariance(totalQty);
        session.setTotalCostVariance(totalCost);
    }

    private String generateSessionNumber() {
        Long nextVal = documentNumbers.next("count_session");
        return "CNT-" + LocalDate.now().getYear() + "-" + String.format("%04d", nextVal);
    }
}
