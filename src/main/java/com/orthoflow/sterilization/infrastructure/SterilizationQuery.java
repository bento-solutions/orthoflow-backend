package com.orthoflow.sterilization.infrastructure;

import com.orthoflow.sterilization.application.dto.SterilizationDtos.CycleSummary;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.TraceEntry;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlResult;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlType;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;

/** The register and the cycle lists, read straight from SQL: they join four tables and are only ever read. */
@Component
@RequiredArgsConstructor
public class SterilizationQuery {

    private static final String TRACE = """
            SELECT e.id, e.occurred_at, e.action, e.item_id, i.code AS item_code, i.name AS item_name, i.kind,
                   e.patient_id, pa.first_name, pa.last_name, pa.patient_code, e.appointment_id, e.cycle_id,
                   c.cycle_number, a.name AS autoclave_name, c.control_result, u.first_name || ' ' || u.last_name AS performed_by, e.note
            FROM sterilization_events e
            JOIN sterilization_items i ON i.id = e.item_id
            LEFT JOIN patients pa ON pa.id = e.patient_id
            LEFT JOIN sterilization_cycles c ON c.id = e.cycle_id
            LEFT JOIN autoclaves a ON a.id = c.autoclave_id
            LEFT JOIN users u ON u.id = e.performed_by
            """;

    private static final String CYCLE = """
            SELECT c.id, c.autoclave_id, a.name AS autoclave_name, c.cycle_number, c.program, c.started_at, c.finished_at,
                   u.first_name || ' ' || u.last_name AS operator_name, c.control_type, c.control_result, c.controlled_at,
                   (SELECT count(*) FROM sterilization_cycle_items ci WHERE ci.cycle_id = c.id) AS item_count
            FROM sterilization_cycles c
            JOIN autoclaves a ON a.id = c.autoclave_id
            LEFT JOIN users u ON u.id = c.operator_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<TraceEntry> TRACE_MAPPER = (rs, n) -> {
        String first = rs.getString("first_name");
        String last = rs.getString("last_name");
        return new TraceEntry(rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("action"),
                rs.getObject("item_id", UUID.class), rs.getString("item_code"), rs.getString("item_name"), Kind.valueOf(rs.getString("kind")),
                rs.getObject("patient_id", UUID.class), first == null ? null : first + " " + last, rs.getString("patient_code"),
                rs.getObject("appointment_id", UUID.class), rs.getObject("cycle_id", UUID.class),
                (Integer) rs.getObject("cycle_number"), rs.getString("autoclave_name"), rs.getString("control_result"),
                rs.getString("performed_by"), rs.getString("note"));
    };

    private static final RowMapper<CycleSummary> CYCLE_MAPPER = (rs, n) -> new CycleSummary(rs.getObject("id", UUID.class),
            rs.getObject("autoclave_id", UUID.class), rs.getString("autoclave_name"), rs.getInt("cycle_number"), rs.getString("program"),
            rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class), rs.getString("operator_name"),
            ControlType.valueOf(rs.getString("control_type")), ControlResult.valueOf(rs.getString("control_result")),
            rs.getObject("controlled_at", OffsetDateTime.class), rs.getInt("item_count"));

    // ── Register ──
    public List<TraceEntry> forItem(UUID practice, UUID itemId, int limit) {
        return jdbc.query(TRACE + " WHERE e.practice_id = :practice AND e.item_id = :item ORDER BY e.occurred_at DESC, e.id LIMIT :limit",
                new MapSqlParameterSource("practice", practice).addValue("item", itemId).addValue("limit", limit), TRACE_MAPPER);
    }

    /** Everything used on a patient, newest first. */
    public List<TraceEntry> usedOnPatient(UUID practice, UUID patientId) {
        return jdbc.query(TRACE + " WHERE e.practice_id = :practice AND e.patient_id = :patient AND e.action = 'USED' ORDER BY e.occurred_at DESC",
                new MapSqlParameterSource("practice", practice).addValue("patient", patientId), TRACE_MAPPER);
    }

    public List<TraceEntry> usedAtAppointment(UUID practice, UUID appointmentId) {
        return jdbc.query(TRACE + " WHERE e.practice_id = :practice AND e.appointment_id = :appointment AND e.action = 'USED' ORDER BY e.occurred_at",
                new MapSqlParameterSource("practice", practice).addValue("appointment", appointmentId), TRACE_MAPPER);
    }

    /** Every use of an item that was last sterilised in this cycle: who the load touched. */
    public List<TraceEntry> usesOfCycle(UUID practice, UUID cycleId) {
        return jdbc.query(TRACE + " WHERE e.practice_id = :practice AND e.cycle_id = :cycle AND e.action = 'USED' ORDER BY e.occurred_at",
                new MapSqlParameterSource("practice", practice).addValue("cycle", cycleId), TRACE_MAPPER);
    }

    // ── Cycles ──
    public List<CycleSummary> cycles(UUID practice, OffsetDateTime from, OffsetDateTime to, UUID autoclaveId, ControlResult result) {
        MapSqlParameterSource params = new MapSqlParameterSource("practice", practice).addValue("from", from).addValue("to", to);
        StringBuilder sql = new StringBuilder(CYCLE).append(" WHERE c.practice_id = :practice AND c.started_at >= :from AND c.started_at < :to");
        if (autoclaveId != null) {
            sql.append(" AND c.autoclave_id = :autoclave");
            params.addValue("autoclave", autoclaveId);
        }
        if (result != null) {
            sql.append(" AND c.control_result = :result");
            params.addValue("result", result.name());
        }
        sql.append(" ORDER BY c.started_at DESC");
        return jdbc.query(sql.toString(), params, CYCLE_MAPPER);
    }

    public Optional<CycleSummary> cycle(UUID practice, UUID id) {
        return jdbc.query(CYCLE + " WHERE c.practice_id = :practice AND c.id = :id",
                new MapSqlParameterSource("practice", practice).addValue("id", id), CYCLE_MAPPER).stream().findFirst();
    }

    public List<CycleSummary> pendingControls(UUID practice) {
        return jdbc.query(CYCLE + " WHERE c.practice_id = :practice AND c.control_result = 'PENDING' ORDER BY c.started_at",
                new MapSqlParameterSource("practice", practice), CYCLE_MAPPER);
    }

    public List<UUID> itemIdsOfCycle(UUID cycleId) {
        return jdbc.queryForList("SELECT item_id FROM sterilization_cycle_items WHERE cycle_id = :cycle",
                new MapSqlParameterSource("cycle", cycleId), UUID.class);
    }

    public void addItemsToCycle(UUID cycleId, Collection<UUID> itemIds) {
        for (UUID item : itemIds) {
            jdbc.update("INSERT INTO sterilization_cycle_items (cycle_id, item_id) VALUES (:cycle, :item)",
                    new MapSqlParameterSource("cycle", cycleId).addValue("item", item));
        }
    }

    // ── Dashboard ──
    public Map<State, Long> countsByState(UUID practice) {
        Map<State, Long> out = new EnumMap<>(State.class);
        for (State s : State.values()) {
            out.put(s, 0L);
        }
        jdbc.query("SELECT state, count(*) AS n FROM sterilization_items WHERE practice_id = :practice AND active GROUP BY state",
                new MapSqlParameterSource("practice", practice), rs -> {
                    out.put(State.valueOf(rs.getString("state")), rs.getLong("n"));
                });
        return out;
    }

    /** Whether an appointment belongs to the patient and the clinic, so a use cannot be attached to someone else's visit. */
    public boolean appointmentBelongsTo(UUID practice, UUID appointmentId, UUID patientId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM appointments WHERE id = :id AND practice_id = :practice AND patient_id = :patient",
                new MapSqlParameterSource("id", appointmentId).addValue("practice", practice).addValue("patient", patientId), Integer.class);
        return n != null && n > 0;
    }
}
