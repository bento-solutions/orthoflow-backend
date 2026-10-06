package com.orthoflow.retrocession.application.service;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.retrocession.application.dto.RetrocessionDtos.*;
import com.orthoflow.retrocession.domain.model.RetrocessionAdvance;
import com.orthoflow.retrocession.domain.model.RetrocessionRule;
import com.orthoflow.retrocession.infrastructure.RetrocessionAdvanceJpaRepository;
import com.orthoflow.retrocession.infrastructure.RetrocessionRuleJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import com.orthoflow.common.tenancy.PracticeZone;

/**
 * The terms a collaborator is paid on, and the advances handed over ahead of a
 * statement. Editing a rule never rewrites a validated statement, which froze its
 * own copy of the terms, so a rule can be corrected without disturbing history.
 */
@Service
@RequiredArgsConstructor
public class RetrocessionRuleService {

    private final RetrocessionRuleJpaRepository rules;
    private final RetrocessionAdvanceJpaRepository advances;
    private final PractitionerService practitioners;
    private final JdbcTemplate jdbc;
    private final PracticeZone practiceZone;

    // ── Rules ──
    @Transactional(readOnly = true)
    public List<RuleView> listRules(UUID practiceId, UUID practitionerId) {
        List<RetrocessionRule> rows = practitionerId == null
                ? rules.findByPracticeIdOrderByPractitionerIdAscEffectiveFromDesc(practiceId)
                : rules.findByPracticeIdAndPractitionerIdOrderByEffectiveFromDesc(practiceId, practitionerId);
        Map<UUID, Practitioner> names = practitioners.byIds(rows.stream().map(RetrocessionRule::getPractitionerId).distinct().toList());
        return rows.stream().map(r -> view(r, names.get(r.getPractitionerId()))).toList();
    }

    @Transactional
    public RuleView createRule(UUID practiceId, UUID actorId, RuleRequest r) {
        Practitioner practitioner = practitioners.require(practiceId, r.practitionerId());
        RetrocessionRule rule = RetrocessionRule.builder().practiceId(practiceId).practitionerId(r.practitionerId()).createdBy(actorId).build();
        apply(rule, r);
        return view(save(rule), practitioner);
    }

    @Transactional
    public RuleView updateRule(UUID practiceId, UUID id, RuleRequest r) {
        RetrocessionRule rule = requireRule(practiceId, id);
        if (!rule.getPractitionerId().equals(r.practitionerId())) {
            throw new ValidationException("A rule cannot be moved to another practitioner; create a new one");
        }
        apply(rule, r);
        return view(save(rule), practitioners.require(practiceId, rule.getPractitionerId()));
    }

    @Transactional
    public void deleteRule(UUID practiceId, UUID id) {
        rules.delete(requireRule(practiceId, id));
    }

    private void apply(RetrocessionRule rule, RuleRequest r) {
        if (r.effectiveTo() != null && r.effectiveTo().isBefore(r.effectiveFrom())) {
            throw new ValidationException("A rule cannot end before it starts");
        }
        Map<String, BigDecimal> overrides = new HashMap<>();
        if (r.overrides() != null) {
            r.overrides().forEach((category, rate) -> {
                String key = category == null ? "" : category.trim();
                if (key.isEmpty() || key.length() > 50) {
                    throw new ValidationException("A category override needs a category name of up to 50 characters");
                }
                if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new ValidationException("A category percentage must be between 0 and 100");
                }
                overrides.put(key, rate);
            });
        }
        rule.setBasis(r.basis());
        rule.setRatePercent(r.ratePercent());
        rule.setDeductLabFees(r.deductLabFees());
        rule.setFixedMonthlyAmount(r.fixedMonthlyAmount() == null ? BigDecimal.ZERO : r.fixedMonthlyAmount());
        rule.setEffectiveFrom(r.effectiveFrom());
        rule.setEffectiveTo(r.effectiveTo());
        rule.setNotes(r.notes() == null || r.notes().isBlank() ? null : r.notes().trim());
        rule.getOverrides().clear();
        rule.getOverrides().putAll(overrides);
    }

    private RetrocessionRule save(RetrocessionRule rule) {
        try {
            return rules.saveAndFlush(rule);
        } catch (DataIntegrityViolationException e) {
            // retrocession_rules_no_overlap: two rules for one practitioner cannot cover the same day.
            throw new ConflictException("This practitioner already has a rule that covers part of these dates");
        }
    }

    private RetrocessionRule requireRule(UUID practiceId, UUID id) {
        return rules.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Rule not found"));
    }

    private static RuleView view(RetrocessionRule r, Practitioner p) {
        return new RuleView(r.getId(), r.getPractitionerId(), p == null ? null : p.getDisplayName(), r.getBasis(), r.getRatePercent(),
                r.isDeductLabFees(), r.getFixedMonthlyAmount(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getNotes(),
                new TreeMap<>(r.getOverrides()));
    }

    // ── Advances ──
    @Transactional(readOnly = true)
    public List<AdvanceView> listAdvances(UUID practiceId, UUID practitionerId) {
        List<RetrocessionAdvance> rows = practitionerId == null
                ? advances.findByPracticeIdOrderByAdvanceDateDescCreatedAtDesc(practiceId)
                : advances.findByPracticeIdAndPractitionerIdOrderByAdvanceDateDescCreatedAtDesc(practiceId, practitionerId);
        Map<UUID, BigDecimal> settled = settledByAdvance(rows.stream().map(RetrocessionAdvance::getId).toList());
        Map<UUID, Practitioner> names = practitioners.byIds(rows.stream().map(RetrocessionAdvance::getPractitionerId).distinct().toList());
        return rows.stream().map(a -> advanceView(a, names.get(a.getPractitionerId()),
                a.getAmount().subtract(settled.getOrDefault(a.getId(), BigDecimal.ZERO)))).toList();
    }

    @Transactional
    public AdvanceView createAdvance(UUID practiceId, UUID actorId, AdvanceRequest r) {
        Practitioner practitioner = practitioners.require(practiceId, r.practitionerId());
        LocalDate date = r.advanceDate() != null ? r.advanceDate() : LocalDate.now(practiceZone.of(practiceId));
        RetrocessionAdvance saved = advances.save(RetrocessionAdvance.builder().practiceId(practiceId)
                .practitionerId(r.practitionerId()).advanceDate(date).amount(r.amount())
                .method(r.method() == null || r.method().isBlank() ? null : r.method().trim())
                .notes(r.notes() == null || r.notes().isBlank() ? null : r.notes().trim()).createdBy(actorId).build());
        return advanceView(saved, practitioner, saved.getAmount());
    }

    /** An advance already deducted from a statement is part of that statement's record and stays. */
    @Transactional
    public void deleteAdvance(UUID practiceId, UUID id) {
        RetrocessionAdvance advance = advances.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Advance not found"));
        Integer used = jdbc.queryForObject("SELECT count(*) FROM retrocession_advance_settlements WHERE advance_id = ?", Integer.class, id);
        if (used != null && used > 0) {
            throw new ConflictException("This advance was deducted on a validated statement and cannot be deleted");
        }
        advances.delete(advance);
    }

    private Map<UUID, BigDecimal> settledByAdvance(List<UUID> ids) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.query("""
                SELECT st.advance_id, sum(st.amount) AS settled
                FROM retrocession_advance_settlements st JOIN retrocession_statements x ON x.id = st.statement_id AND x.voided_at IS NULL
                WHERE st.advance_id IN (""" + in + ") GROUP BY st.advance_id", rs -> {
            out.put(rs.getObject("advance_id", UUID.class), rs.getBigDecimal("settled"));
        }, ids.toArray());
        return out;
    }

    private static AdvanceView advanceView(RetrocessionAdvance a, Practitioner p, BigDecimal outstanding) {
        return new AdvanceView(a.getId(), a.getPractitionerId(), p == null ? null : p.getDisplayName(), a.getAdvanceDate(),
                a.getAmount(), outstanding, a.getMethod(), a.getNotes());
    }
}
