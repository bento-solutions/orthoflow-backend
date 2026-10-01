package com.orthoflow.consultation.application.service;

import com.orthoflow.consultation.domain.model.Consultation;
import com.orthoflow.consultation.domain.model.ConsultationStatus;
import com.orthoflow.consultation.domain.repository.ConsultationRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Shared test doubles for the consultation services. */
final class ConsultationFixtures {

    private ConsultationFixtures() {
    }

    static final UUID PATIENT = UUID.randomUUID();
    static final UUID ACTOR = UUID.randomUUID();
    static final UUID VOICE_SESSION = UUID.randomUUID();

    /** A repository that keeps rows in memory and counts writes. */
    static final class InMemoryConsultations implements ConsultationRepository {
        final Map<UUID, Consultation> rows = new LinkedHashMap<>();
        final List<ConsultationStatus> savedStatuses = new ArrayList<>();
        int saves;

        @Override
        public Consultation save(Consultation consultation) {
            if (consultation.getId() == null) consultation.prePersist();
            saves++;
            savedStatuses.add(consultation.getStatus());
            rows.put(consultation.getId(), consultation);
            return consultation;
        }

        @Override
        public Optional<Consultation> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<Consultation> findByPatient(UUID patientId) {
            return rows.values().stream().filter(c -> c.getPatientId().equals(patientId)).toList();
        }

        @Override
        public Optional<Consultation> findOpenByPatient(UUID patientId) {
            return findByPatient(patientId).stream().filter(c -> c.getStatus().isOpen()).findFirst();
        }

        @Override
        public boolean saveReviewState(UUID id, String reviewState) {
            Consultation c = rows.get(id);
            if (c == null || !c.getStatus().isOpen()) return false;
            c.setReviewState(reviewState);
            c.touch();
            return true;
        }

        @Override
        public List<Consultation> findIdleOpen(OffsetDateTime before) {
            return rows.values().stream()
                    .filter(c -> c.getStatus().isOpen())
                    .filter(c -> (c.getLastActivityAt() != null ? c.getLastActivityAt() : c.getStartedAt()).isBefore(before))
                    .toList();
        }
    }

    static Consultation consultation(ConsultationStatus status) {
        Consultation c = Consultation.builder()
                .patientId(PATIENT)
                .actorId(ACTOR)
                .voiceSessionId(VOICE_SESSION)
                .status(status)
                .locale("fr-MA")
                .patientInformedAt(OffsetDateTime.now())
                .transcript("")
                .build();
        c.prePersist();
        return c;
    }
}
