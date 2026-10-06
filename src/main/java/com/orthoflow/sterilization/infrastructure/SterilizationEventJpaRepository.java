package com.orthoflow.sterilization.infrastructure;

import com.orthoflow.sterilization.domain.model.SterilizationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SterilizationEventJpaRepository extends JpaRepository<SterilizationEvent, UUID> {
}
