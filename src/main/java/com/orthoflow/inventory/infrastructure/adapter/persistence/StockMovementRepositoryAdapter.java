package com.orthoflow.inventory.infrastructure.adapter.persistence;

import com.orthoflow.inventory.domain.model.MovementType;
import com.orthoflow.inventory.domain.model.StockMovement;
import com.orthoflow.inventory.domain.model.StockMovementFilter;
import com.orthoflow.inventory.domain.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class StockMovementRepositoryAdapter implements StockMovementRepository {

    private final StockMovementJpaRepository jpaRepository;

    @Override
    public StockMovement save(StockMovement stockMovement) {
        return jpaRepository.save(stockMovement);
    }

    @Override
    public Optional<StockMovement> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<StockMovement> findAll() {
        return jpaRepository.findAll();
    }

    /**
     * Executes a filtered + sorted query via JPA Specifications.
     * Sort field is whitelisted in {@link StockMovementFilter} — no SQL injection risk.
     */
    @Override
    public List<StockMovement> findAll(StockMovementFilter filter) {
        Sort sort = filter.isAscending()
                ? Sort.by(filter.sortBy()).ascending()
                : Sort.by(filter.sortBy()).descending();

        return jpaRepository.findAll(StockMovementSpecification.from(filter), sort);
    }

    @Override
    public Page<StockMovement> findAll(StockMovementFilter filter, Pageable pageable) {
        Sort sort = filter.isAscending()
                ? Sort.by(filter.sortBy()).ascending()
                : Sort.by(filter.sortBy()).descending();

        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        return jpaRepository.findAll(StockMovementSpecification.from(filter), sorted);
    }

    @Override
    public List<StockMovement> findByStockItemId(UUID stockItemId) {
        return jpaRepository.findByStockItemId(stockItemId);
    }

    @Override
    public List<StockMovement> findByMovementTypeAndCreatedAtBetween(MovementType movementType, OffsetDateTime start, OffsetDateTime end) {
        return jpaRepository.findByMovementTypeAndCreatedAtBetween(movementType, start, end);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public java.math.BigDecimal costOfMovementsBetween(MovementType movementType, OffsetDateTime start, OffsetDateTime end) {
        return jpaRepository.costOfMovementsBetween(movementType, start, end);
    }

    @Override
    public List<ItemTotal> topItemsBetween(MovementType movementType, OffsetDateTime start, OffsetDateTime end, int limit) {
        return jpaRepository.topItemsBetween(movementType, start, end, org.springframework.data.domain.PageRequest.of(0, limit)).stream()
                .map(r -> new ItemTotal((UUID) r[0], (String) r[1], (java.math.BigDecimal) r[2], (java.math.BigDecimal) r[3]))
                .toList();
    }
}
