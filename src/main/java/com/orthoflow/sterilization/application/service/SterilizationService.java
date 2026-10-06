package com.orthoflow.sterilization.application.service;

import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.*;
import com.orthoflow.sterilization.domain.model.SterilizationEvent.Action;
import com.orthoflow.sterilization.domain.model.SterilizationItem;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import com.orthoflow.sterilization.infrastructure.SterilizationItemJpaRepository;
import com.orthoflow.sterilization.infrastructure.SterilizationQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Trays, instruments, handpieces and endo kits moving through READY → USED → DIRTY →
 * PROCESSED → READY. Every move happens on a row lock and is written to the
 * register with who did it and, for a use, on whom, so "which instruments touched
 * this patient" and "who did this tray touch" are both one query.
 */
@Service
@RequiredArgsConstructor
public class SterilizationService {

    /** What a printed label encodes. A prefix tells this app's codes from any other QR code a camera might see. */
    public static final String QR_PREFIX = "OFS1:";

    static final int DEFAULT_SHELF_LIFE_DAYS = 30;

    private final SterilizationItemJpaRepository items;
    private final SterilizationRegister register;
    private final SterilizationQuery query;
    private final EndoService endo;
    private final PatientLookup patients;
    private final LiveEventPublisher liveEvents;

    // ── Read ──
    @Transactional(readOnly = true)
    public List<ItemView> list(UUID practiceId, State state, Kind kind, String search, boolean includeRetired) {
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        return items.findByPracticeIdOrderByCodeAsc(practiceId).stream()
                .filter(i -> includeRetired || i.isActive())
                .filter(i -> state == null || i.getState() == state)
                .filter(i -> kind == null || i.getKind() == kind)
                .filter(i -> needle == null || i.getCode().toLowerCase(Locale.ROOT).contains(needle)
                        || i.getName().toLowerCase(Locale.ROOT).contains(needle))
                .map(ItemViews::of).toList();
    }

    @Transactional(readOnly = true)
    public ItemView get(UUID practiceId, UUID id) {
        return ItemViews.of(require(practiceId, id));
    }

    /** Resolves what a camera or a keyboard produced: the printed label's text, the bare token, or the item's own code. */
    @Transactional(readOnly = true)
    public ItemView scan(UUID practiceId, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("Nothing was scanned");
        }
        String code = raw.trim();
        String token = code.startsWith(QR_PREFIX) ? code.substring(QR_PREFIX.length()) : code;
        return items.findByQrTokenAndPracticeId(token, practiceId)
                .or(() -> items.findByPracticeIdAndCodeIgnoreCase(practiceId, code))
                .map(ItemViews::of).orElseThrow(() -> new NotFoundException("No sterilization item matches that code"));
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID practiceId, Integer shelfLifeDays) {
        int shelfLife = shelfLifeDays == null || shelfLifeDays < 1 ? DEFAULT_SHELF_LIFE_DAYS : shelfLifeDays;
        OffsetDateTime expiry = OffsetDateTime.now().minusDays(shelfLife);
        List<SterilizationItem> active = items.findByPracticeIdOrderByCodeAsc(practiceId).stream().filter(SterilizationItem::isActive).toList();
        return new Dashboard(query.countsByState(practiceId),
                active.stream().filter(i -> i.lubricationDue() && (i.getState() == State.USED || i.getState() == State.DIRTY))
                        .sorted(Comparator.comparing(SterilizationItem::getCode)).map(ItemViews::of).toList(),
                active.stream().filter(i -> i.getState() == State.READY && i.getStateChangedAt().isBefore(expiry))
                        .sorted(Comparator.comparing(SterilizationItem::getStateChangedAt)).map(ItemViews::of).toList(),
                query.pendingControls(practiceId), endo.alerts(practiceId), shelfLife);
    }

    // ── Register and edit ──
    @Transactional
    public ItemView create(UUID practiceId, UUID actorId, ItemRequest r) {
        String code = r.code().trim();
        if (items.existsByPracticeIdAndCodeIgnoreCase(practiceId, code)) {
            throw new ConflictException("An item with the code " + code + " already exists");
        }
        OffsetDateTime now = OffsetDateTime.now();
        SterilizationItem item = items.save(SterilizationItem.builder().practiceId(practiceId).code(code).name(r.name().trim()).kind(r.kind())
                .serialNumber(blank(r.serialNumber())).notes(blank(r.notes())).state(r.markSterile() ? State.READY : State.DIRTY)
                .stateChangedAt(now).build());
        register.record(item, Action.REGISTERED, null, actorId, now, null, null, null,
                r.markSterile() ? "Registered as already sterile" : "Registered; must pass a cycle before use");
        liveEvents.publish(practiceId, "sterilization", item.getId());
        return ItemViews.of(item);
    }

    @Transactional
    public ItemView update(UUID practiceId, UUID id, ItemUpdate r) {
        SterilizationItem item = require(practiceId, id);
        item.setName(r.name().trim());
        item.setSerialNumber(blank(r.serialNumber()));
        item.setNotes(blank(r.notes()));
        return ItemViews.of(items.save(item));
    }

    @Transactional
    public ItemView retire(UUID practiceId, UUID actorId, UUID id, String reason) {
        SterilizationItem item = lock(practiceId, id);
        if (item.isActive()) {
            item.setActive(false);
            register.record(item, Action.RETIRED, item.getState(), actorId, OffsetDateTime.now(), null, null, null, reason);
            items.save(item);
            liveEvents.publish(practiceId, "sterilization", id);
        }
        return ItemViews.of(item);
    }

    // ── The cycle ──
    @Transactional
    public ItemView use(UUID practiceId, UUID actorId, UUID id, UseRequest r) {
        if (!patients.exists(r.patientId())) {
            throw new NotFoundException("Patient not found");
        }
        if (r.appointmentId() != null && !query.appointmentBelongsTo(practiceId, r.appointmentId(), r.patientId())) {
            throw new ValidationException("That appointment is not one of this patient's");
        }
        SterilizationItem item = lockActive(practiceId, id);
        State from = item.getState();
        OffsetDateTime now = OffsetDateTime.now();
        item.use(now);
        // A spent endo file refuses the use; the exception rolls the transaction back, leaving the item as it was.
        if (item.getKind() == Kind.ENDO_KIT) {
            endo.recordUse(practiceId, item);
        }
        register.record(item, Action.USED, from, actorId, now, item.getLastCycleId(), r.patientId(), r.appointmentId(), r.note());
        liveEvents.publish(practiceId, "sterilization", id);
        return ItemViews.of(items.save(item));
    }

    @Transactional
    public ItemView sendToCleaning(UUID practiceId, UUID actorId, UUID id) {
        SterilizationItem item = lockActive(practiceId, id);
        State from = item.getState();
        OffsetDateTime now = OffsetDateTime.now();
        item.sendToCleaning(now);
        register.record(item, Action.DIRTY, from, actorId, now, null, null, null, null);
        liveEvents.publish(practiceId, "sterilization", id);
        return ItemViews.of(items.save(item));
    }

    @Transactional
    public ItemView lubricate(UUID practiceId, UUID actorId, UUID id, String product) {
        SterilizationItem item = lockActive(practiceId, id);
        OffsetDateTime now = OffsetDateTime.now();
        item.lubricate(now);
        register.record(item, Action.LUBRICATED, item.getState(), actorId, now, null, null, null, product);
        liveEvents.publish(practiceId, "sterilization", id);
        return ItemViews.of(items.save(item));
    }

    // ── Helpers ──
    private SterilizationItem require(UUID practiceId, UUID id) {
        return items.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Item not found"));
    }

    private SterilizationItem lock(UUID practiceId, UUID id) {
        return items.findForUpdate(id, practiceId).orElseThrow(() -> new NotFoundException("Item not found"));
    }

    private SterilizationItem lockActive(UUID practiceId, UUID id) {
        SterilizationItem item = lock(practiceId, id);
        if (!item.isActive()) {
            throw new ConflictException(item.getCode() + " has been retired and cannot be used");
        }
        return item;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
