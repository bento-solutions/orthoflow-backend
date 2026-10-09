package com.orthoflow.auth.domain.repository;

import com.orthoflow.auth.domain.model.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    User save(User user);
    Optional<User> findById(UUID id);
    Optional<User> findByEmail(String email);
    /** Users are not filtered by Hibernate (sign-in finds them across clinics), so listing them always names the clinic. */
    List<User> findAllInPractice(UUID practiceId);
    boolean existsAny();
}
