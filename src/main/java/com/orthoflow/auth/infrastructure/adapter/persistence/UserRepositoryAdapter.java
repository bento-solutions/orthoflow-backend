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

    /**
     * Addresses are matched whatever their case: "Dr.Alami@…" typed on a phone is the same person.
     * Should two accounts differ only by case (possible before this), only an exact match is taken.
     */
    @Override
    public Optional<User> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String typed = email.trim();
        List<User> matches = jpaRepository.findAllByEmailIgnoringCase(typed);
        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }
        return matches.stream().filter(u -> u.getEmail().equals(typed)).findFirst();
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
