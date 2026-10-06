package com.orthoflow.sterilization.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.*;
import com.orthoflow.sterilization.domain.model.Autoclave;
import com.orthoflow.sterilization.domain.model.SterilizationCycle;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlResult;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlType;
import com.orthoflow.sterilization.domain.model.SterilizationEvent.Action;
import com.orthoflow.sterilization.domain.model.SterilizationItem;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import com.orthoflow.sterilization.infrastructure.AutoclaveJpaRepository;
import com.orthoflow.sterilization.infrastructure.SterilizationCycleJpaRepository;
import com.orthoflow.sterilization.infrastructure.SterilizationItemJpaRepository;
import com.orthoflow.sterilization.infrastructure.SterilizationQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Autoclave cycles: which machine, which cycle number, which program, who loaded
 * it, which items, and what the control said.
 *
 * <p>Loading a cycle takes DIRTY items to PROCESSED. A passed control releases them
 * to READY; a failed one sends everything from that cycle back to DIRTY and says
 * who it touched in the meantime. A cycle that passed on its chemical indicator may
 * later fail on a biological one, which is why FAILED is accepted after PASSED.
 */
@Service
@RequiredArgsConstructor
public class CycleService {

    private final AutoclaveJpaRepository autoclaves;
    private final SterilizationCycleJpaRepository cycles;
    private final SterilizationItemJpaRepository items;
    private final SterilizationRegister register;
    private final SterilizationQuery query;
    private final StaffNotifier notifier;
    private final LiveEventPublisher liveEvents;

    // ── Autoclaves ──
    @Transactional(readOnly = true)
    public List<AutoclaveView> listAutoclaves(UUID practiceId) {
        return autoclaves.findByPracticeIdOrderByNameAsc(practiceId).stream().map(CycleService::view).toList();
    }

    @Transactional
    public AutoclaveView createAutoclave(UUID practiceId, AutoclaveRequest r) {
        if (autoclaves.existsByPracticeIdAndNameIgnoreCase(practiceId, r.name().trim())) {
            throw new ConflictException("An autoclave named " + r.name().trim() + " already exists");
        }
        return view(autoclaves.save(Autoclave.builder().practiceId(practiceId).name(r.name().trim()).model(blank(r.model()))
                .serialNumber(blank(r.serialNumber())).active(r.active() == null || r.active()).build()));
    }

    @Transactional
    public AutoclaveView updateAutoclave(UUID practiceId, UUID id, AutoclaveRequest r) {
        Autoclave a = autoclaves.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Autoclave not found"));
        if (!a.getName().equalsIgnoreCase(r.name().trim()) && autoclaves.existsByPracticeIdAndNameIgnoreCase(practiceId, r.name().trim())) {
            throw new ConflictException("An autoclave named " + r.name().trim() + " already exists");
        }
        a.setName(r.name().trim());
        a.setModel(blank(r.model()));
        a.setSerialNumber(blank(r.serialNumber()));
        if (r.active() != null) {
            a.setActive(r.active());
        }
        return view(autoclaves.save(a));
    }

    // ── Cycles ──
    @Transactional(readOnly = true)
    public List<CycleSummary> list(UUID practiceId, LocalDate from, LocalDate to, UUID autoclaveId, ControlResult result, ZoneId zone) {
        LocalDate end = to == null ? LocalDate.now(zone) : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (end.isBefore(start)) {
            throw new ValidationException("Give a period: from must not be after to");
        }
        return query.cycles(practiceId, start.atStartOfDay(zone).toOffsetDateTime(), end.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                autoclaveId, result);
    }

    @Transactional(readOnly = true)
    public CycleView get(UUID practiceId, UUID id) {
        return view(practiceId, requireSummary(practiceId, id), List.of());
    }

    @Transactional
    public CycleView create(UUID practiceId, UUID actorId, CycleRequest r) {
        Autoclave autoclave = autoclaves.findForUpdate(r.autoclaveId(), practiceId).orElseThrow(() -> new NotFoundException("Autoclave not found"));
        if (!autoclave.isActive()) {
            throw new ConflictException("The autoclave " + autoclave.getName() + " is not in service");
        }
        Set<UUID> ids = new LinkedHashSet<>(r.itemIds());
        List<SterilizationItem> loaded = items.findAllForUpdate(ids, practiceId);
        if (loaded.size() != ids.size()) {
            throw new NotFoundException("One or more items were not found");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime started = r.startedAt() == null ? now : r.startedAt();
        if (r.finishedAt() != null && r.finishedAt().isBefore(started)) {
            throw new ValidationException("A cycle cannot finish before it starts");
        }
        for (SterilizationItem item : loaded) {
            if (!item.isActive()) {
                throw new ConflictException(item.getCode() + " has been retired and cannot be loaded");
            }
        }

        // Flushed now: the load below is linked to the cycle with a plain JDBC insert, which Hibernate
        // does not wait for, and the foreign key needs the cycle row to exist by then.
        SterilizationCycle cycle = cycles.saveAndFlush(SterilizationCycle.builder().practiceId(practiceId).autoclaveId(autoclave.getId())
                .cycleNumber(cycles.maxNumber(autoclave.getId()) + 1).program(r.program().trim()).startedAt(started).finishedAt(r.finishedAt())
                .operatorId(actorId).controlType(r.controlType() == null ? ControlType.CHEMICAL : r.controlType()).notes(blank(r.notes())).build());

        List<String> warnings = new ArrayList<>();
        for (SterilizationItem item : loaded) {
            if (item.lubricationDue()) {
                warnings.add("Handpiece " + item.getCode() + " has not been lubricated since it was last used");
            }
            State from = item.getState();
            item.process(cycle.getId(), now);
            register.record(item, Action.PROCESSED, from, actorId, now, cycle.getId(), null, null, null);
        }
        items.saveAll(loaded);
        query.addItemsToCycle(cycle.getId(), ids);
        liveEvents.publish(practiceId, "sterilization", cycle.getId());
        return view(practiceId, requireSummary(practiceId, cycle.getId()), warnings);
    }

    /**
     * Records what the control said. PASSED releases the load; FAILED recalls it and
     * tells the staff who handle sterilization, with the number of uses already at stake.
     */
    @Transactional
    public CycleView recordControl(UUID practiceId, UUID actorId, UUID id, ControlRequest r) {
        SterilizationCycle cycle = cycles.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Cycle not found"));
        OffsetDateTime now = OffsetDateTime.now();
        if (cycle.decide(r.result(), actorId, blank(r.note()), now)) {
            // Flushed: the summaries below are read with plain SQL and must see the new result.
            cycles.saveAndFlush(cycle);
            List<SterilizationItem> load = items.findByPracticeIdAndLastCycleId(practiceId, id);
            if (r.result() == ControlResult.PASSED) {
                for (SterilizationItem item : load) {
                    if (item.getState() == State.PROCESSED) {
                        State from = item.getState();
                        item.release(now);
                        register.record(item, Action.RELEASED, from, actorId, now, id, null, null, null);
                    }
                }
            } else {
                for (SterilizationItem item : load) {
                    if (item.getState() == State.READY || item.getState() == State.PROCESSED) {
                        State from = item.getState();
                        item.recall(now);
                        register.record(item, Action.RECALLED, from, actorId, now, id, null, null, "Control failed: " + (r.note() == null ? "" : r.note()));
                    }
                }
                alertFailure(practiceId, requireSummary(practiceId, id));
            }
            items.saveAll(load);
            liveEvents.publish(practiceId, "sterilization", id);
        }
        return view(practiceId, requireSummary(practiceId, id), List.of());
    }

    /** What a cycle put at stake: where its items are now, and every patient they were used on since. */
    @Transactional(readOnly = true)
    public Exposure exposure(UUID practiceId, UUID id) {
        CycleSummary summary = requireSummary(practiceId, id);
        List<ItemView> load = items.findByPracticeIdAndLastCycleId(practiceId, id).stream().map(ItemViews::of).toList();
        return new Exposure(summary, load, query.usesOfCycle(practiceId, id));
    }

    // ── Helpers ──
    private void alertFailure(UUID practiceId, CycleSummary c) {
        int uses = query.usesOfCycle(practiceId, c.id()).size();
        notifier.toPermission(practiceId, Permission.STERILIZATION_MANAGE, MessagePurpose.GENERIC,
                "Cycle de stérilisation en échec : " + c.autoclaveName() + " n° " + c.number(),
                "Le contrôle du cycle n° " + c.number() + " (" + c.autoclaveName() + ") a échoué. Le matériel concerné est remis à retraiter"
                        + (uses > 0 ? "; " + uses + " utilisation(s) sur des patients sont à examiner dans l'onglet Exposition." : "."),
                "STERILIZATION_CYCLE", c.id());
    }

    private CycleSummary requireSummary(UUID practiceId, UUID id) {
        return query.cycle(practiceId, id).orElseThrow(() -> new NotFoundException("Cycle not found"));
    }

    private CycleView view(UUID practiceId, CycleSummary summary, List<String> warnings) {
        SterilizationCycle cycle = cycles.findByIdAndPracticeId(summary.id(), practiceId).orElseThrow(() -> new NotFoundException("Cycle not found"));
        List<ItemView> load = items.findAllById(query.itemIdsOfCycle(summary.id())).stream()
                .sorted(Comparator.comparing(SterilizationItem::getCode)).map(ItemViews::of).toList();
        return new CycleView(summary, cycle.getNotes(), cycle.getControlNote(), load, warnings);
    }

    private static AutoclaveView view(Autoclave a) {
        return new AutoclaveView(a.getId(), a.getName(), a.getModel(), a.getSerialNumber(), a.isActive());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
