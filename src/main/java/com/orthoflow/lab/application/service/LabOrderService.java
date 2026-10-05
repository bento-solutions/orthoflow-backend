package com.orthoflow.lab.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.inventory.domain.model.Supplier;
import com.orthoflow.inventory.domain.model.SupplierKind;
import com.orthoflow.inventory.domain.repository.SupplierRepository;
import com.orthoflow.lab.application.dto.LabDtos.*;
import com.orthoflow.lab.application.port.AppointmentSlots;
import com.orthoflow.lab.application.port.LabExpenseRecorder;
import com.orthoflow.lab.domain.model.LabOrder;
import com.orthoflow.lab.domain.model.LabStatus;
import com.orthoflow.lab.infrastructure.LabOrderJpaRepository;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientIdentity;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orders sent to the dental lab. The one thing that makes this more than a list is
 * the fitting link: a piece is ordered for an appointment, and the system says so
 * when that appointment is booked before the piece is due back, or is days away
 * with nothing received yet — the patient is in the chair and the retainer is
 * still at the lab.
 */
@Service
@RequiredArgsConstructor
public class LabOrderService {

    private static final int FITTING_SOON_DAYS = 2;

    private final LabOrderJpaRepository orders;
    private final SupplierRepository suppliers;
    private final PatientLookup patientLookup;
    private final PractitionerService practitionerService;
    private final AppointmentSlots slots;
    private final LabExpenseRecorder expenseRecorder;
    private final StaffNotifier notifier;
    private final PracticeZone practiceZone;
    private final PdfService pdfService;
    private final LetterheadProvider letterheadProvider;
    private final LiveEventPublisher liveEvents;

    @Transactional(readOnly = true)
    public List<Lab> labs() {
        return suppliers.findAll().stream().filter(s -> s.isActive() && s.getKind() == SupplierKind.LAB)
                .map(s -> new Lab(s.getId(), s.getName(), s.getPhone(), s.getEmail())).toList();
    }

    @Transactional
    public View create(UUID practiceId, UUID actorId, Request r) {
        validate(practiceId, r);
        LabOrder order = LabOrder.builder().practiceId(practiceId).patientId(r.patientId()).labId(r.labId())
                .practitionerId(r.practitionerId()).itemType(r.itemType()).description(r.description())
                .sentDate(r.sentDate() != null ? r.sentDate() : today(practiceId)).dueDate(r.dueDate()).urgent(r.urgent())
                .cost(r.cost()).fittingAppointmentId(r.fittingAppointmentId()).notes(r.notes()).createdBy(actorId).build();
        LabOrder saved = orders.save(order);
        liveEvents.publish(practiceId, "lab-order", saved.getId());
        return views(practiceId, List.of(saved)).get(0);
    }

    @Transactional
    public View update(UUID practiceId, UUID id, Request r) {
        LabOrder o = require(practiceId, id);
        validate(practiceId, r);
        o.setPatientId(r.patientId());
        o.setLabId(r.labId());
        o.setPractitionerId(r.practitionerId());
        o.setItemType(r.itemType());
        o.setDescription(r.description());
        if (r.sentDate() != null) o.setSentDate(r.sentDate());
        o.setDueDate(r.dueDate());
        o.setUrgent(r.urgent());
        o.setCost(r.cost());
        o.setFittingAppointmentId(r.fittingAppointmentId());
        o.setNotes(r.notes());
        liveEvents.publish(practiceId, "lab-order", id);
        return views(practiceId, List.of(orders.save(o))).get(0);
    }

    /**
     * Moves an order along. Arrival books the lab's fee as an expense (once) and
     * tells the staff who manage orders, so the fitting can be confirmed the same day.
     */
    @Transactional
    public View transition(UUID practiceId, UUID actorId, UUID id, Transition t) {
        LabOrder o = require(practiceId, id);
        if (!o.getStatus().next().contains(t.status())) {
            throw new ConflictException("An order that is " + o.getStatus() + " cannot become " + t.status());
        }
        LocalDate date = t.date() != null ? t.date() : today(practiceId);
        o.setStatus(t.status());
        switch (t.status()) {
            case RECEIVED -> {
                o.setReceivedDate(date);
                Supplier lab = suppliers.findById(o.getLabId()).orElse(null);
                expenseRecorder.recordLabFee(practiceId, o.getId(), lab == null ? null : lab.getName(), o.getCost(), date, actorId);
                notifier.toPermission(practiceId, Permission.LAB_ORDERS_MANAGE, MessagePurpose.LAB_ORDER_RECEIVED, "Travail reçu du laboratoire",
                        describe(o) + " est arrivé" + (o.getFittingAppointmentId() != null ? " — la pose est planifiée." : "."), "LAB_ORDER", o.getId());
            }
            case FITTED -> o.setFittedDate(date);
            case REMAKE -> {
                o.setReceivedDate(null);
                o.setFittedDate(null);
            }
            default -> {
            }
        }
        liveEvents.publish(practiceId, "lab-order", id);
        return views(practiceId, List.of(orders.save(o))).get(0);
    }

    @Transactional(readOnly = true)
    public List<View> list(Filter f) {
        ZoneId zone = practiceZone.of(f.practiceId());
        List<LabStatus> statuses = f.statuses() == null ? List.of() : f.statuses();
        List<LabOrder> rows = orders.search(f.practiceId(), statuses.isEmpty(), statuses.isEmpty() ? List.of(LabStatus.SENT) : statuses,
                f.dueFrom(), f.dueTo(),
                // Bounds rather than nulls: PostgreSQL cannot type an untyped null timestamp parameter.
                f.updatedFrom() == null ? OffsetDateTime.parse("1970-01-01T00:00:00Z") : f.updatedFrom().atStartOfDay(zone).toOffsetDateTime(),
                f.updatedTo() == null ? OffsetDateTime.parse("2200-01-01T00:00:00Z") : f.updatedTo().plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                f.urgentOnly(), f.patientId(), f.labId());
        List<View> out = views(f.practiceId(), rows);
        return out.stream().filter(v -> !f.overdueOnly() || v.warnings().contains("OVERDUE"))
                .filter(v -> f.search() == null || f.search().isBlank() || contains(v, f.search())).toList();
    }

    @Transactional(readOnly = true)
    public View get(UUID practiceId, UUID id) {
        return views(practiceId, List.of(require(practiceId, id))).get(0);
    }

    /** The order as a document to send with the piece, in French. */
    @Transactional(readOnly = true)
    public byte[] document(UUID practiceId, UUID id, String lang) {
        LabOrder o = require(practiceId, id);
        PatientIdentity patient = patientLookup.findIdentity(o.getPatientId()).orElseThrow(() -> new NotFoundException("Patient not found"));
        Supplier lab = suppliers.findById(o.getLabId()).orElseThrow(() -> new NotFoundException("Lab not found"));
        Practitioner practitioner = o.getPractitionerId() == null ? null : practitionerService.require(practiceId, o.getPractitionerId());
        Letterhead letterhead = letterheadProvider.forPractice(practiceId);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", letterhead);
        model.put("order", Map.of("itemType", o.getItemType().name(), "description", o.getDescription() == null ? "" : o.getDescription(),
                "sentDate", o.getSentDate() == null ? "" : o.getSentDate().toString(), "dueDate", o.getDueDate() == null ? "" : o.getDueDate().toString(),
                "urgent", o.isUrgent(), "notes", o.getNotes() == null ? "" : o.getNotes(), "reference", o.getId().toString().substring(0, 8).toUpperCase()));
        model.put("patient", patient);
        model.put("lab", Map.of("name", lab.getName(), "contact", lab.getContactName() == null ? "" : lab.getContactName()));
        model.put("practitioner", practitioner == null ? "" : practitioner.getDisplayName());
        return pdfService.render("lab-order", model, "ar".equals(lang) || "en".equals(lang) ? lang : "fr");
    }

    private void validate(UUID practiceId, Request r) {
        if (!patientLookup.exists(r.patientId())) {
            throw new NotFoundException("Patient not found");
        }
        Supplier lab = suppliers.findById(r.labId()).orElseThrow(() -> new NotFoundException("Lab not found"));
        if (lab.getKind() != SupplierKind.LAB) {
            throw new ValidationException(lab.getName() + " is not marked as a laboratory");
        }
        if (r.practitionerId() != null) {
            practitionerService.require(practiceId, r.practitionerId());
        }
        if (r.dueDate() != null && r.sentDate() != null && r.dueDate().isBefore(r.sentDate())) {
            throw new ValidationException("The due date cannot be before the sent date");
        }
        if (r.fittingAppointmentId() != null) {
            var slot = slots.find(r.fittingAppointmentId()).filter(s -> practiceId.equals(s.practiceId()))
                    .orElseThrow(() -> new NotFoundException("Appointment not found"));
            if (!slot.patientId().equals(r.patientId())) {
                throw new ValidationException("That appointment belongs to a different patient");
            }
        }
    }

    private List<View> views(UUID practiceId, List<LabOrder> rows) {
        ZoneId zone = practiceZone.of(practiceId);
        LocalDate today = LocalDate.now(zone);
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(rows.stream().map(LabOrder::getPatientId).distinct().toList());
        Map<UUID, Supplier> labs = new HashMap<>();
        rows.stream().map(LabOrder::getLabId).distinct().forEach(id -> suppliers.findById(id).ifPresent(s -> labs.put(id, s)));
        Map<UUID, Practitioner> practitioners = practitionerService.byIds(rows.stream().map(LabOrder::getPractitionerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(o -> {
            OffsetDateTime fittingAt = o.getFittingAppointmentId() == null ? null
                    : slots.find(o.getFittingAppointmentId()).map(AppointmentSlots.Slot::dateTime).orElse(null);
            PatientSummary p = patients.get(o.getPatientId());
            Supplier lab = labs.get(o.getLabId());
            Practitioner pr = practitioners.get(o.getPractitionerId());
            return new View(o.getId(), o.getPatientId(), p == null ? null : p.fullName(), o.getLabId(), lab == null ? null : lab.getName(),
                    o.getPractitionerId(), pr == null ? null : pr.getDisplayName(), o.getItemType(), o.getDescription(), o.getSentDate(),
                    o.getDueDate(), o.getStatus(), o.isUrgent(), o.getCost(), o.getFittingAppointmentId(), fittingAt, o.getReceivedDate(),
                    o.getFittedDate(), o.getNotes(), warnings(o, fittingAt, today, zone), o.getCreatedAt(), o.getUpdatedAt());
        }).toList();
    }

    /** What deserves a second look, as codes the screen turns into words. */
    static List<String> warnings(LabOrder o, OffsetDateTime fittingAt, LocalDate today, ZoneId zone) {
        List<String> w = new ArrayList<>();
        LocalDate fittingDay = fittingAt == null ? null : fittingAt.atZoneSameInstant(zone).toLocalDate();
        boolean outstanding = o.getStatus().isOutstanding();
        if (outstanding && o.getDueDate() != null && o.getDueDate().isBefore(today)) {
            w.add("OVERDUE");
        }
        if (outstanding && fittingDay != null && o.getDueDate() != null && fittingDay.isBefore(o.getDueDate())) {
            w.add("FITTING_BEFORE_DUE");
        }
        if (outstanding && fittingDay != null && !fittingDay.isBefore(today) && !fittingDay.isAfter(today.plusDays(FITTING_SOON_DAYS))) {
            w.add("NOT_RECEIVED_BEFORE_FITTING");
        }
        return w;
    }

    private LabOrder require(UUID practiceId, UUID id) {
        return orders.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Lab order not found"));
    }

    private LocalDate today(UUID practiceId) {
        return LocalDate.now(practiceZone.of(practiceId));
    }

    private String describe(LabOrder o) {
        String patient = patientLookup.findSummary(o.getPatientId()).map(PatientSummary::fullName).orElse("un patient");
        return o.getItemType().name().toLowerCase() + " de " + patient;
    }

    private static boolean contains(View v, String term) {
        String t = term.toLowerCase();
        return (v.patientName() != null && v.patientName().toLowerCase().contains(t))
                || (v.labName() != null && v.labName().toLowerCase().contains(t))
                || (v.description() != null && v.description().toLowerCase().contains(t));
    }
}
