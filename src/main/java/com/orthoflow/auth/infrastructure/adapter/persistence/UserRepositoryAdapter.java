package com.orthoflow.auth.infrastructure.adapter.persistence;

import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpaRepository;

    @Override
    public User save(User user) {
        return jpaRepository.save(user);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email);
    }

    @Override
    public List<User> findAllInPractice(UUID practiceId) {
        return jpaRepository.findByPracticeIdOrderByLastNameAscFirstNameAsc(practiceId);
    }

    @Override
    public boolean existsAny() {
        return jpaRepository.count() > 0;
    }
}
