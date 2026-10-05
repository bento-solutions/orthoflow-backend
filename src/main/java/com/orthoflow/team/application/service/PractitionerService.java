package com.orthoflow.team.application.service;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.team.application.dto.PractitionerDtos.*;
import com.orthoflow.team.domain.model.Practitioner;
import com.orthoflow.team.infrastructure.adapter.persistence.PractitionerJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PractitionerService {

    private final PractitionerJpaRepository practitioners;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<Response> list(UUID practiceId, boolean includeInactive) {
        var rows = includeInactive
                ? practitioners.findByPracticeIdOrderByDisplayOrderAscDisplayNameAsc(practiceId)
                : practitioners.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscDisplayNameAsc(practiceId);
        return rows.stream().map(Response::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findIdByUser(UUID userId) {
        return practitioners.findByUserId(userId).map(Practitioner::getId);
    }

    /** Names for a set of ids, for screens that show a doctor next to a row. */
    @Transactional(readOnly = true)
    public Map<UUID, Practitioner> byIds(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return practitioners.findAllById(ids).stream().collect(Collectors.toMap(Practitioner::getId, Function.identity()));
    }

    @Transactional(readOnly = true)
    public Practitioner require(UUID practiceId, UUID id) {
        return practitioners.findByIdAndPracticeId(id, practiceId)
                .orElseThrow(() -> new NotFoundException("Practitioner not found"));
    }

    @Transactional
    public Response create(UUID practiceId, Request request) {
        assertUserFree(request.userId(), null);
        int next = practitioners.findByPracticeIdOrderByDisplayOrderAscDisplayNameAsc(practiceId).stream()
                .mapToInt(Practitioner::getDisplayOrder).max().orElse(-1) + 1;
        Practitioner saved = practitioners.save(Practitioner.builder()
                .practiceId(practiceId)
                .userId(request.userId())
                .displayName(request.displayName().trim())
                .color(request.color() != null ? request.color() : "#2563eb")
                .specialty(request.specialty())
                .inpe(request.inpe())
                .displayOrder(next)
                .active(request.active() == null || request.active())
                .build());
        return Response.from(saved);
    }

    @Transactional
    public Response update(UUID practiceId, UUID id, Request request) {
        Practitioner p = require(practiceId, id);
        assertUserFree(request.userId(), p.getId());
        p.setDisplayName(request.displayName().trim());
        p.setUserId(request.userId());
        if (request.color() != null) p.setColor(request.color());
        p.setSpecialty(request.specialty());
        p.setInpe(request.inpe());
        if (request.active() != null) p.setActive(request.active());
        return Response.from(practitioners.save(p));
    }

    @Transactional
    public void reorder(UUID practiceId, List<UUID> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            Practitioner p = require(practiceId, orderedIds.get(i));
            p.setDisplayOrder(i);
        }
    }

    @Transactional(readOnly = true)
    public List<Unmatched> unmatchedNames() {
        return jdbc.query("SELECT doctor_name, row_count FROM practitioner_backfill_unmatched ORDER BY row_count DESC",
                (rs, i) -> new Unmatched(rs.getString(1), rs.getInt(2)));
    }

    /** Points every treatment carrying this free-text name at a practitioner, and clears it from the report. */
    @Transactional
    public int resolveUnmatched(UUID practiceId, ResolveUnmatched request) {
        require(practiceId, request.practitionerId());
        int updated = jdbc.update("""
                UPDATE patient_treatments SET practitioner_id = ?
                WHERE practitioner_id IS NULL AND btrim(doctor_name) = ? AND practice_id = ?
                """, request.practitionerId(), request.doctorName().trim(), practiceId);
        jdbc.update("DELETE FROM practitioner_backfill_unmatched WHERE doctor_name = ?", request.doctorName().trim());
        return updated;
    }

    private void assertUserFree(UUID userId, UUID exceptPractitionerId) {
        if (userId == null) {
            return;
        }
        practitioners.findByUserId(userId).ifPresent(existing -> {
            if (!existing.getId().equals(exceptPractitionerId)) {
                throw new ConflictException("That user is already linked to another practitioner");
            }
        });
    }
}
