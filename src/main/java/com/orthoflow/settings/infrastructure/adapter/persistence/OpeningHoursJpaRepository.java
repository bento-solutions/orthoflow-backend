package com.orthoflow.settings.infrastructure.adapter.persistence;

import com.orthoflow.settings.domain.model.OpeningHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OpeningHoursJpaRepository extends JpaRepository<OpeningHours, UUID> {

    List<OpeningHours> findByPracticeIdOrderByWeekdayAsc(UUID practiceId);
}
