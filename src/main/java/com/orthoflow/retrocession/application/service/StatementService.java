package com.orthoflow.retrocession.application.service;

import com.orthoflow.common.numbering.DocumentNumbers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.retrocession.application.dto.RetrocessionDtos.*;
import com.orthoflow.retrocession.domain.model.RetrocessionRule;
import com.orthoflow.retrocession.infrastructure.RetrocessionRuleJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Validated retrocession statements: the frozen record of what a practitioner was
 * paid for a period. The figures are computed here, on the server, at the moment
 * of validation: the request names a practitioner and a period, never an amount.
 * The database then refuses any change to the statement except voiding it
 * (migration V50), so what was validated is what stays on record.
 */
@Service
@RequiredArgsConstructor
public class StatementService {

    private static final String SELECT = """
            SELECT s.*, pr.display_name AS practitioner_name,
                   COALESCE((SELECT sum(p.amount) FROM retrocession_payouts p WHERE p.statement_id = s.id), 0) AS paid
            FROM retrocession_statements s LEFT JOIN practitioners pr ON pr.id = s.practitioner_id
            """;

    private final JdbcTemplate jdbc;
    private final RetrocessionService retrocessions;
    private final RetrocessionRuleJpaRepository ruleRepository;
    private final PractitionerService practitioners;
    private final PracticeZone practiceZone;
    private final ObjectMapper objectMapper;
    private final LiveEventPublisher liveEvents;
    private final ActivityLog activityLog;
    private final PdfService pdfService;
    private final LetterheadProvider letterheadProvider;
    private final DocumentNumbers documentNumbers;

    // ── Validate ──
    @Transactional
    public StatementView validate(UUID practiceId, UUID actorId, ValidateRequest r) {
        RetrocessionService.checkRange(r.from(), r.to());
        practitioners.require(practiceId, r.practitionerId());
        // Two people validating one practitioner at once must not both settle the same advance.
        jdbc.queryForList("SELECT id FROM retrocession_advances WHERE practitioner_id = ? FOR UPDATE", r.practitionerId());

        Integer clashes = jdbc.queryForObject("""
                SELECT count(*) FROM retrocession_statements
                WHERE practitioner_id = ? AND voided_at IS NULL AND daterange(period_from, period_to, '[]') && daterange(?, ?, '[]')""",
                Integer.class, r.practitionerId(), r.from(), r.to());
        if (clashes != null && clashes > 0) {
            throw new ConflictException("A statement already covers part of this period for this practitioner; void it first");
        }

        Filters filters = new Filters(r.methods(), r.statuses());
        Simulation simulation = retrocessions.simulate(practiceId, r.practitionerId(), r.from(), r.to(), filters);
        PractitionerFigures f = simulation.practitioners().stream().filter(p -> p.practitionerId().equals(r.practitionerId()))
                .findFirst().orElseThrow(() -> new ValidationException("No retrocession rule applies to this practitioner in this period"));
        if (f.lines().isEmpty()) {
            throw new ValidationException("There is nothing to pay for this period");
        }

        UUID id = UUID.randomUUID();
        LocalDate today = LocalDate.now(practiceZone.of(practiceId));
        Long seq = documentNumbers.next(practiceId, "retrocession_statement");
        String number = "RET-%d-%05d".formatted(today.getYear(), seq);
        try {
            jdbc.update("""
                    INSERT INTO retrocession_statements (id, practice_id, practitioner_id, statement_number, period_from, period_to,
                        base_amount, lab_deduction, variable_amount, fixed_amount, adjustment_amount, gross_amount, advances_deducted,
                        net_amount, filters, rules_snapshot, notes, validated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""",
                    id, practiceId, r.practitionerId(), number, r.from(), r.to(), f.base(), f.labDeduction(), f.variable(), f.fixed(),
                    f.adjustment(), f.gross(), f.advancesApplied(), f.net(), json(filters), json(rulesSnapshot(practiceId, r)),
                    r.notes() == null || r.notes().isBlank() ? null : r.notes().trim(), actorId);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A statement already covers part of this period for this practitioner; void it first");
        }

        int order = 0;
        List<Object[]> lineArgs = new ArrayList<>();
        for (LineView l : f.lines()) {
            lineArgs.add(new Object[]{UUID.randomUUID(), id, l.kind().name(), l.date(), l.invoiceId(), l.invoiceNumber(), l.patientCode(),
                    l.category() == null || l.category().isEmpty() ? null : l.category(), l.label(),
                    l.base() == null ? BigDecimal.ZERO : l.base(), l.ratePercent(), l.amount(), order++});
        }
        jdbc.batchUpdate("""
                INSERT INTO retrocession_statement_lines (id, statement_id, kind, item_date, invoice_id, invoice_number, patient_code,
                    category, label, base_amount, rate_percent, amount, sort_order)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", lineArgs);
        for (AdvanceSettlement s : f.settlements()) {
            jdbc.update("INSERT INTO retrocession_advance_settlements (statement_id, advance_id, amount) VALUES (?, ?, ?)",
                    id, s.advanceId(), s.applied());
        }

        activityLog.record(practiceId, "RETROCESSION", id, "VALIDATED", Map.of("number", number, "gross", f.gross().toString(), "net", f.net().toString()));
        liveEvents.publish(practiceId, "retrocession", id);
        return get(practiceId, id);
    }

    // ── Read ──
    @Transactional(readOnly = true)
    public List<StatementSummary> list(UUID practiceId, UUID practitionerId, LocalDate from, LocalDate to, boolean includeVoided) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE s.practice_id = ?");
        List<Object> args = new ArrayList<>(List.of(practiceId));
        if (practitionerId != null) {
            sql.append(" AND s.practitioner_id = ?");
            args.add(practitionerId);
        }
        if (from != null && to != null) {
            sql.append(" AND s.period_from <= ? AND s.period_to >= ?");
            args.add(to);
            args.add(from);
        }
        if (!includeVoided) {
            sql.append(" AND s.voided_at IS NULL");
        }
        sql.append(" ORDER BY s.period_to DESC, s.validated_at DESC");
        return jdbc.query(sql.toString(), (rs, i) -> summary(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public StatementView get(UUID practiceId, UUID id) {
        List<StatementView> rows = jdbc.query(SELECT + " WHERE s.id = ? AND s.practice_id = ?", (RowMapper<StatementView>) this::detail, id, practiceId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Statement not found");
        }
        StatementView head = rows.get(0);
        List<LineView> lines = jdbc.query("SELECT * FROM retrocession_statement_lines WHERE statement_id = ? ORDER BY sort_order",
                (rs, i) -> new LineView(com.orthoflow.retrocession.application.service.RetrocessionCalculator.Kind.valueOf(rs.getString("kind")),
                        rs.getObject("item_date", LocalDate.class), rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"),
                        rs.getString("patient_code"), rs.getString("category"), rs.getString("label"), rs.getBigDecimal("base_amount"),
                        rs.getBigDecimal("rate_percent"), rs.getBigDecimal("amount")), id);
        List<AdvanceSettlement> settlements = jdbc.query("""
                SELECT st.advance_id, a.advance_date, a.amount AS advance_amount, st.amount
                FROM retrocession_advance_settlements st JOIN retrocession_advances a ON a.id = st.advance_id
                WHERE st.statement_id = ? ORDER BY a.advance_date""",
                (rs, i) -> new AdvanceSettlement(rs.getObject("advance_id", UUID.class), rs.getObject("advance_date", LocalDate.class),
                        rs.getBigDecimal("advance_amount"), rs.getBigDecimal("amount")), id);
        List<PayoutView> payouts = jdbc.query("SELECT * FROM retrocession_payouts WHERE statement_id = ? ORDER BY paid_date, created_at",
                (rs, i) -> new PayoutView(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"), rs.getObject("paid_date", LocalDate.class),
                        rs.getString("method"), rs.getString("reference"), rs.getString("notes"),
                        rs.getObject("created_at", OffsetDateTime.class)), id);
        return new StatementView(head.id(), head.number(), head.practitionerId(), head.practitionerName(), head.from(), head.to(),
                head.base(), head.labDeduction(), head.variable(), head.fixed(), head.adjustment(), head.gross(), head.advancesDeducted(),
                head.net(), head.paid(), head.status(), head.filters(), head.notes(), head.validatedAt(), head.voidedAt(), head.voidReason(),
                lines, settlements, payouts);
    }

    /** Only the owner of a statement and the one practitioner it names ever need this; callers check access. */
    @Transactional(readOnly = true)
    public UUID practitionerOf(UUID practiceId, UUID id) {
        List<UUID> rows = jdbc.queryForList("SELECT practitioner_id FROM retrocession_statements WHERE id = ? AND practice_id = ?",
                UUID.class, id, practiceId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Statement not found");
        }
        return rows.get(0);
    }

    // ── Void and pay ──
    @Transactional
    public StatementView voidStatement(UUID practiceId, UUID actorId, UUID id, String reason) {
        StatementView s = get(practiceId, id);
        if (s.status() == PayStatus.VOID) {
            return s;
        }
        if (!s.payouts().isEmpty()) {
            throw new ConflictException("Money has already been paid out on this statement and it cannot be voided");
        }
        jdbc.update("UPDATE retrocession_statements SET voided_at = now(), voided_by = ?, void_reason = ? WHERE id = ? AND voided_at IS NULL",
                actorId, reason.trim(), id);
        activityLog.record(practiceId, "RETROCESSION", id, "VOIDED", Map.of("number", s.number(), "reason", reason.trim()));
        liveEvents.publish(practiceId, "retrocession", id);
        return get(practiceId, id);
    }

    @Transactional
    public StatementView recordPayout(UUID practiceId, UUID actorId, UUID id, PayoutRequest r) {
        // Lock the statement so two payouts cannot both pass the "not more than is owed" check.
        jdbc.queryForList("SELECT id FROM retrocession_statements WHERE id = ? AND practice_id = ? FOR UPDATE", id, practiceId);
        StatementView s = get(practiceId, id);
        if (s.status() == PayStatus.VOID) {
            throw new ConflictException("A voided statement cannot be paid");
        }
        BigDecimal remaining = s.net().subtract(s.paid());
        if (r.amount().compareTo(remaining) > 0) {
            throw new ValidationException("Only " + remaining.toPlainString() + " remains to be paid on this statement");
        }
        LocalDate date = r.paidDate() != null ? r.paidDate() : LocalDate.now(practiceZone.of(practiceId));
        jdbc.update("INSERT INTO retrocession_payouts (id, statement_id, amount, paid_date, method, reference, notes, recorded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, r.amount(), date, blank(r.method()), blank(r.reference()), blank(r.notes()), actorId);
        activityLog.record(practiceId, "RETROCESSION", id, "PAID", Map.of("number", s.number(), "amount", r.amount().toString()));
        liveEvents.publish(practiceId, "retrocession", id);
        return get(practiceId, id);
    }

    // ── PDF ──
    @Transactional(readOnly = true)
    public byte[] document(UUID practiceId, UUID id, String lang) {
        StatementView s = get(practiceId, id);
        String language = "ar".equals(lang) || "en".equals(lang) ? lang : "fr";
        DecimalFormat money = formatter(language);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("letterhead", letterheadProvider.forPractice(practiceId));
        model.put("labels", StatementLabels.of(language));
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("number", s.number());
        st.put("practitioner", s.practitionerName() == null ? "" : s.practitionerName());
        st.put("from", s.from().toString());
        st.put("to", s.to().toString());
        st.put("voided", s.status() == PayStatus.VOID);
        st.put("voidReason", s.voidReason() == null ? "" : s.voidReason());
        st.put("validatedAt", s.validatedAt() == null ? "" : s.validatedAt().toLocalDate().toString());
        st.put("notes", s.notes() == null ? "" : s.notes());
        st.put("base", money.format(s.base()));
        st.put("labDeduction", money.format(s.labDeduction()));
        st.put("variable", money.format(s.variable()));
        st.put("fixed", money.format(s.fixed()));
        st.put("adjustment", money.format(s.adjustment()));
        st.put("gross", money.format(s.gross()));
        st.put("advances", money.format(s.advancesDeducted()));
        st.put("net", money.format(s.net()));
        st.put("paid", money.format(s.paid()));
        st.put("hasLab", s.labDeduction().signum() != 0);
        st.put("hasFixed", s.fixed().signum() != 0);
        st.put("hasAdjustment", s.adjustment().signum() != 0);
        st.put("hasAdvances", s.advancesDeducted().signum() != 0);
        model.put("statement", st);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (LineView l : s.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", l.date() == null ? "" : l.date().toString());
            m.put("reference", l.invoiceNumber() == null ? "" : l.invoiceNumber());
            m.put("patient", l.patientCode() == null ? "" : l.patientCode());
            m.put("what", l.label() != null ? l.label() : l.category() == null ? "" : l.category());
            m.put("base", l.base() == null || l.kind() == RetrocessionCalculator.Kind.ADJUSTMENT ? "" : money.format(l.base()));
            m.put("rate", l.ratePercent() == null ? "" : l.ratePercent().stripTrailingZeros().toPlainString() + " %");
            m.put("amount", money.format(l.amount()));
            lines.add(m);
        }
        model.put("lines", lines);
        return pdfService.render("retrocession-statement", model, language);
    }

    // ── Helpers ──
    private List<Map<String, Object>> rulesSnapshot(UUID practiceId, ValidateRequest r) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RetrocessionRule rule : ruleRepository.findOverlapping(practiceId, r.from(), r.to(), r.practitionerId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rule.getId().toString());
            m.put("basis", rule.getBasis().name());
            m.put("ratePercent", rule.getRatePercent());
            m.put("deductLabFees", rule.isDeductLabFees());
            m.put("fixedMonthlyAmount", rule.getFixedMonthlyAmount());
            m.put("effectiveFrom", rule.getEffectiveFrom().toString());
            m.put("effectiveTo", rule.getEffectiveTo() == null ? null : rule.getEffectiveTo().toString());
            m.put("overrides", new TreeMap<>(rule.getOverrides()));
            out.add(m);
        }
        return out;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise statement snapshot", e);
        }
    }

    private StatementSummary summary(ResultSet rs) throws SQLException {
        BigDecimal net = rs.getBigDecimal("net_amount");
        BigDecimal paid = rs.getBigDecimal("paid");
        return new StatementSummary(rs.getObject("id", UUID.class), rs.getString("statement_number"), rs.getObject("practitioner_id", UUID.class),
                rs.getString("practitioner_name"), rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
                rs.getBigDecimal("gross_amount"), rs.getBigDecimal("advances_deducted"), net, paid, status(rs, net, paid),
                rs.getObject("validated_at", OffsetDateTime.class));
    }

    private StatementView detail(ResultSet rs, int row) throws SQLException {
        BigDecimal net = rs.getBigDecimal("net_amount");
        BigDecimal paid = rs.getBigDecimal("paid");
        Filters filters;
        try {
            filters = objectMapper.readValue(rs.getString("filters"), Filters.class);
        } catch (JsonProcessingException e) {
            filters = Filters.none();
        }
        return new StatementView(rs.getObject("id", UUID.class), rs.getString("statement_number"), rs.getObject("practitioner_id", UUID.class),
                rs.getString("practitioner_name"), rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
                rs.getBigDecimal("base_amount"), rs.getBigDecimal("lab_deduction"), rs.getBigDecimal("variable_amount"),
                rs.getBigDecimal("fixed_amount"), rs.getBigDecimal("adjustment_amount"), rs.getBigDecimal("gross_amount"),
                rs.getBigDecimal("advances_deducted"), net, paid, status(rs, net, paid), filters, rs.getString("notes"),
                rs.getObject("validated_at", OffsetDateTime.class), rs.getObject("voided_at", OffsetDateTime.class),
                rs.getString("void_reason"), List.of(), List.of(), List.of());
    }

    private static PayStatus status(ResultSet rs, BigDecimal net, BigDecimal paid) throws SQLException {
        if (rs.getObject("voided_at") != null) {
            return PayStatus.VOID;
        }
        if (paid.compareTo(net) >= 0) {
            return PayStatus.PAID;
        }
        return paid.signum() > 0 ? PayStatus.PARTIAL : PayStatus.UNPAID;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static DecimalFormat formatter(String lang) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        boolean en = "en".equals(lang);
        symbols.setGroupingSeparator(en ? ',' : ' ');
        symbols.setDecimalSeparator(en ? '.' : ',');
        return new DecimalFormat("#,##0.00", symbols);
    }
}
