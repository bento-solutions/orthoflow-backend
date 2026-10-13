package com.orthoflow.audit.application.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The administrator's view of one clinic's audit trail. Never reads another clinic's rows. */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    @Schema(name = "AuditLogRow")
    public record Row(UUID id, OffsetDateTime occurredAt, AuditCategory category, String action, UUID actorId, String actorLabel,
                      UUID patientId, String targetType, UUID targetId, String method, String route, Integer status, String ip,
                      String detail) {
    }

    @Schema(name = "AuditLogPage")
    public record PageOfRows(List<Row> rows, long total, int page, int size) {
    }

    public record Filter(AuditCategory category, UUID patientId, UUID actorId, OffsetDateTime from, OffsetDateTime to) {
    }

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PageOfRows search(UUID practiceId, Filter filter, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), 200);
        int pageNumber = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE practice_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(practiceId);
        if (filter.category() != null) {
            where.append(" AND category = ?");
            args.add(filter.category().name());
        }
        if (filter.patientId() != null) {
            where.append(" AND patient_id = ?");
            args.add(filter.patientId());
        }
        if (filter.actorId() != null) {
            where.append(" AND actor_id = ?");
            args.add(filter.actorId());
        }
        if (filter.from() != null) {
            where.append(" AND occurred_at >= ?");
            args.add(filter.from());
        }
        if (filter.to() != null) {
            where.append(" AND occurred_at < ?");
            args.add(filter.to());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) pageNumber * pageSize);
        List<Row> rows = jdbc.query("""
                SELECT id, occurred_at, category, action, actor_id, actor_label, patient_id, target_type, target_id, method, route, status, ip, detail
                FROM audit_log""" + where + " ORDER BY occurred_at DESC, id LIMIT ? OFFSET ?",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("occurred_at", OffsetDateTime.class),
                        AuditCategory.valueOf(rs.getString("category")), rs.getString("action"), rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_label"), rs.getObject("patient_id", UUID.class), rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class), rs.getString("method"), rs.getString("route"),
                        (Integer) rs.getObject("status"), rs.getString("ip"), rs.getString("detail")), pageArgs.toArray());
        return new PageOfRows(rows, total == null ? 0 : total, pageNumber, pageSize);
    }
}
