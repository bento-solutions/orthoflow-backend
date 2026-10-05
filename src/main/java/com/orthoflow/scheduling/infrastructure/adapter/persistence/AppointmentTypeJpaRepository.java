package com.orthoflow.scheduling.infrastructure.adapter.persistence;

import com.orthoflow.scheduling.domain.model.AppointmentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentTypeJpaRepository extends JpaRepository<AppointmentType, UUID> {

    List<AppointmentType> findByPracticeIdOrderByDisplayOrderAscNameFrAsc(UUID practiceId);

    List<AppointmentType> findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameFrAsc(UUID practiceId);

    List<AppointmentType> findByPracticeIdAndActiveTrueAndBookableOnlineTrueOrderByDisplayOrderAscNameFrAsc(UUID practiceId);

    Optional<AppointmentType> findByIdAndPracticeId(UUID id, UUID practiceId);

    boolean existsByPracticeIdAndCode(UUID practiceId, String code);
}
