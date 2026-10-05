package com.orthoflow.auth.infrastructure.adapter.persistence;

import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    List<User> findByPracticeIdOrderByLastNameAscFirstNameAsc(UUID practiceId);

    long countByPracticeIdAndRoleAndActiveTrue(UUID practiceId, UserRole role);
}
