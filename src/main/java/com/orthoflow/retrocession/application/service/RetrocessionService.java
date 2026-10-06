package com.orthoflow.retrocession.application.service;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.retrocession.application.dto.RetrocessionDtos.*;
import com.orthoflow.retrocession.application.service.RetrocessionCalculator.Rule;
import com.orthoflow.retrocession.domain.model.Basis;
import com.orthoflow.retrocession.domain.model.RetrocessionRule;
import com.orthoflow.retrocession.infrastructure.RetrocessionQuery;
import com.orthoflow.retrocession.infrastructure.RetrocessionQuery.OutstandingAdvance;
import com.orthoflow.retrocession.infrastructure.RetrocessionRuleJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * What each collaborating doctor would be paid for a period, computed live from
 * the rules and the billing data. Nothing here is stored: validating a period into
 * a statement is {@link StatementService}'s job, and it calls this to get the
 * numbers it freezes, so the simulation and the statement cannot drift apart.
 */
@Service
@RequiredArgsConstructor
public class RetrocessionService {

    private static final int MAX_UNATTRIBUTED_LISTED = 50;

    private final RetrocessionRuleJpaRepository rules;
    private final RetrocessionQuery query;
    private final PractitionerService practitioners;

    @Transactional(readOnly = true)
    public Simulation simulate(UUID practiceId, UUID practitionerId, LocalDate from, LocalDate to, Filters filters) {
        checkRange(from, to);
        Filters f = filters == null ? Filters.none() : filters;
        List<RetrocessionRule> applicable = rules.findOverlapping(practiceId, from, to, practitionerId);
        List<RetrocessionCalculator.Result> results = compute(practiceId, applicable, practitionerId, from, to, f);

        Map<UUID, Practitioner> names = practitioners.byIds(results.stream().map(RetrocessionCalculator.Result::practitionerId).toList());
        List<PractitionerFigures> figures = new ArrayList<>();
        for (RetrocessionCalculator.Result r : results) {
            figures.add(figures(practiceId, r, names.get(r.practitionerId()), to));
        }
        BigDecimal gross = figures.stream().map(PractitionerFigures::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = figures.stream().map(PractitionerFigures::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Simulation(from, to, f, figures, gross, net, unattributed(practiceId, applicable, from, to, f));
    }

    /** What the clinic owes collaborators for a period, before advances: the finance dashboard's retrocession line. */
    @Transactional(readOnly = true)
    public BigDecimal owed(UUID practiceId, LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<RetrocessionRule> applicable = rules.findOverlapping(practiceId, from, to, null);
        return compute(practiceId, applicable, null, from, to, Filters.none()).stream()
                .map(RetrocessionCalculator.Result::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The retrocession accrued on each day of a period, for reports that group by
     * day, week, month or year. Unrounded daily fixed amounts are included, so the
     * sum over any whole period matches {@link #owed} to within a cent.
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, BigDecimal> accruedByDay(UUID practiceId, LocalDate from, LocalDate to) {
        checkRange(from, to);
        List<RetrocessionRule> applicable = rules.findOverlapping(practiceId, from, to, null);
        Map<LocalDate, BigDecimal> out = new TreeMap<>();
        for (RetrocessionCalculator.Result r : compute(practiceId, applicable, null, from, to, Filters.none())) {
            for (RetrocessionCalculator.Line line : r.lines()) {
                if (line.kind() == RetrocessionCalculator.Kind.ITEM || line.kind() == RetrocessionCalculator.Kind.LAB) {
                    out.merge(line.date(), line.amount(), BigDecimal::add);
                }
            }
            r.fixedByDay().forEach((day, amount) -> out.merge(day, amount, BigDecimal::add));
        }
        return out;
    }

    private List<RetrocessionCalculator.Result> compute(UUID practiceId, List<RetrocessionRule> applicable, UUID practitionerId,
                                                        LocalDate from, LocalDate to, Filters f) {
        if (applicable.isEmpty()) {
            return List.of();
        }
        List<Rule> calcRules = applicable.stream().map(RetrocessionService::toCalc).toList();
        Set<Basis> bases = applicable.stream().map(RetrocessionRule::getBasis).collect(Collectors.toCollection(() -> EnumSet.noneOf(Basis.class)));
        List<RetrocessionCalculator.Item> items = new ArrayList<>();
        for (Basis basis : bases) {
            items.addAll(query.items(practiceId, basis, from, to, f.methods(), f.statuses(), true, practitionerId));
        }
        List<RetrocessionCalculator.LabFee> labFees = applicable.stream().anyMatch(RetrocessionRule::isDeductLabFees)
                ? query.labFees(practiceId, from, to, practitionerId) : List.of();
        return RetrocessionCalculator.compute(calcRules, items, labFees, from, to);
    }

    /** Settles outstanding advances oldest first, up to what is owed. */
    private PractitionerFigures figures(UUID practiceId, RetrocessionCalculator.Result r, Practitioner practitioner, LocalDate to) {
        List<OutstandingAdvance> outstanding = query.outstandingAdvances(practiceId, r.practitionerId(), to);
        BigDecimal totalOutstanding = outstanding.stream().map(OutstandingAdvance::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = r.gross();
        List<AdvanceSettlement> settlements = new ArrayList<>();
        for (OutstandingAdvance a : outstanding) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal applied = a.outstanding().min(remaining);
            settlements.add(new AdvanceSettlement(a.id(), a.date(), a.amount(), applied));
            remaining = remaining.subtract(applied);
        }
        BigDecimal applied = r.gross().subtract(remaining);
        return new PractitionerFigures(r.practitionerId(), practitioner == null ? null : practitioner.getDisplayName(),
                r.base(), r.labDeduction(), r.variable(), r.fixed(), r.adjustment(), r.gross(), totalOutstanding, applied,
                remaining, settlements, r.lines().stream().map(RetrocessionService::toView).toList());
    }

    private Unattributed unattributed(UUID practiceId, List<RetrocessionRule> applicable, LocalDate from, LocalDate to, Filters f) {
        Map<UUID, UnattributedInvoice> byInvoice = new LinkedHashMap<>();
        Set<Basis> bases = applicable.stream().map(RetrocessionRule::getBasis).collect(Collectors.toCollection(() -> EnumSet.noneOf(Basis.class)));
        for (Basis basis : bases) {
            for (RetrocessionQuery.UnattributedRow row : query.unattributed(practiceId, basis, from, to, f.methods(), f.statuses())) {
                byInvoice.merge(row.invoiceId(), new UnattributedInvoice(row.invoiceId(), row.invoiceNumber(), row.date(), row.amount()),
                        (a, b) -> a.amount().compareTo(b.amount()) >= 0 ? a : b);
            }
        }
        List<UnattributedInvoice> all = byInvoice.values().stream()
                .sorted(Comparator.comparing(UnattributedInvoice::amount).reversed()).toList();
        BigDecimal total = all.stream().map(UnattributedInvoice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Unattributed(RetrocessionCalculator.money(total), all.size(), all.stream().limit(MAX_UNATTRIBUTED_LISTED).toList());
    }

    public static Rule toCalc(RetrocessionRule r) {
        return new Rule(r.getId(), r.getPractitionerId(), r.getBasis(), r.getRatePercent(), r.isDeductLabFees(),
                r.getFixedMonthlyAmount() == null ? BigDecimal.ZERO : r.getFixedMonthlyAmount(), r.getEffectiveFrom(),
                r.getEffectiveTo(), Map.copyOf(r.getOverrides()));
    }

    public static LineView toView(RetrocessionCalculator.Line l) {
        return new LineView(l.kind(), l.date(), l.invoiceId(), l.invoiceNumber(), l.patientCode(), l.category(), l.label(),
                l.base(), l.ratePercent(), l.amount());
    }

    static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ValidationException("Give a period: from must not be after to");
        }
        if (from.plusYears(5).isBefore(to)) {
            throw new ValidationException("A period is limited to five years");
        }
    }
}
