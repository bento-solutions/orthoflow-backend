package com.orthoflow.patient.application.service;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.patient.application.dto.PatientDirectoryDtos.*;
import com.orthoflow.patient.domain.model.Insurer;
import com.orthoflow.patient.domain.model.ReferralSource;
import com.orthoflow.patient.infrastructure.adapter.persistence.InsurerJpaRepository;
import com.orthoflow.patient.infrastructure.adapter.persistence.ReferralSourceJpaRepository;
import com.orthoflow.patient.infrastructure.adapter.query.PatientDirectoryQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The patient list, its figures, the duplicate check, and the lists a patient form draws from. */
@Service
@RequiredArgsConstructor
public class PatientDirectoryService {

    private final PatientDirectoryQuery query;
    private final InsurerJpaRepository insurers;
    private final ReferralSourceJpaRepository referralSources;
    private final PracticeZone practiceZone;

    @Transactional(readOnly = true)
    public Page<Row> list(PatientDirectoryQuery.Filter filter, String sort, boolean descending, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        return new PageImpl<>(query.page(filter, sort, descending, safePage, safeSize),
                PageRequest.of(safePage, safeSize), query.count(filter));
    }

    @Transactional(readOnly = true)
    public Kpis kpis(UUID practiceId) {
        var zone = practiceZone.of(practiceId);
        OffsetDateTime monthStart = OffsetDateTime.now().atZoneSameInstant(zone).toLocalDate().withDayOfMonth(1)
                .atStartOfDay(zone).toOffsetDateTime();
        return query.kpis(practiceId, monthStart);
    }

    @Transactional(readOnly = true)
    public List<DuplicatePair> duplicates(UUID practiceId) {
        return query.duplicates(practiceId, 200);
    }

    // ── Insurers ──
    @Transactional(readOnly = true)
    public List<InsurerResponse> insurers(UUID practiceId, boolean includeInactive) {
        var rows = includeInactive ? insurers.findByPracticeIdOrderByNameAsc(practiceId)
                : insurers.findByPracticeIdAndActiveTrueOrderByNameAsc(practiceId);
        return rows.stream().map(PatientDirectoryService::toResponse).toList();
    }

    @Transactional
    public InsurerResponse createInsurer(UUID practiceId, InsurerRequest r) {
        if (insurers.existsByPracticeIdAndCodeIgnoreCase(practiceId, r.code().trim())) {
            throw new ConflictException("An insurer with code " + r.code() + " already exists");
        }
        return toResponse(insurers.save(Insurer.builder().practiceId(practiceId).code(r.code().trim().toUpperCase())
                .name(r.name().trim()).kind(r.kind() == null ? "PRIVATE" : r.kind()).active(r.active() == null || r.active()).build()));
    }

    @Transactional
    public InsurerResponse updateInsurer(UUID practiceId, UUID id, InsurerRequest r) {
        Insurer i = insurers.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Insurer not found"));
        i.setName(r.name().trim());
        if (r.kind() != null) i.setKind(r.kind());
        if (r.active() != null) i.setActive(r.active());
        return toResponse(insurers.save(i));
    }

    // ── Referral sources ──
    @Transactional(readOnly = true)
    public List<ReferralSourceResponse> referralSources(UUID practiceId, boolean includeInactive) {
        var rows = includeInactive ? referralSources.findByPracticeIdOrderByDisplayOrderAscNameAsc(practiceId)
                : referralSources.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(practiceId);
        return rows.stream().map(PatientDirectoryService::toResponse).toList();
    }

    @Transactional
    public ReferralSourceResponse createReferralSource(UUID practiceId, ReferralSourceRequest r) {
        if (referralSources.existsByPracticeIdAndNameIgnoreCase(practiceId, r.name().trim())) {
            throw new ConflictException("That referral source already exists");
        }
        return toResponse(referralSources.save(ReferralSource.builder().practiceId(practiceId).name(r.name().trim())
                .active(r.active() == null || r.active()).displayOrder(r.displayOrder() == null ? 0 : r.displayOrder()).build()));
    }

    @Transactional
    public ReferralSourceResponse updateReferralSource(UUID practiceId, UUID id, ReferralSourceRequest r) {
        ReferralSource s = referralSources.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Referral source not found"));
        s.setName(r.name().trim());
        if (r.active() != null) s.setActive(r.active());
        if (r.displayOrder() != null) s.setDisplayOrder(r.displayOrder());
        return toResponse(referralSources.save(s));
    }

    private static InsurerResponse toResponse(Insurer i) {
        return new InsurerResponse(i.getId(), i.getCode(), i.getName(), i.getKind(), i.isActive());
    }

    private static ReferralSourceResponse toResponse(ReferralSource s) {
        return new ReferralSourceResponse(s.getId(), s.getName(), s.isActive(), s.getDisplayOrder());
    }
}
