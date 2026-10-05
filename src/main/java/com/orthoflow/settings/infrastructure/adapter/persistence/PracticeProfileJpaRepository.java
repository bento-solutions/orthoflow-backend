package com.orthoflow.settings.infrastructure.adapter.persistence;

import com.orthoflow.settings.domain.model.PracticeProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PracticeProfileJpaRepository extends JpaRepository<PracticeProfile, UUID> {
}
