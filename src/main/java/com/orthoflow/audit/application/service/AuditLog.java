package com.orthoflow.audit.application.service;

import com.orthoflow.platform.web.ClientAddress;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the security and patient-access audit trail (V65). Every row is written outside the
 * transaction of the action it describes, so it is kept when that action is rolled back: a failed
 * sign-in throws, and a refused export must still leave its trace.
 *
 * <p>Who did it, from where: the signed-in user and the current request are read from the
 * ambient context, at the time of the call, unless the caller says otherwise. A failure to write
 * never breaks the action being audited (a clinic must be able to treat a patient while the audit
 * table is unwell), but it is logged as an error with the event, so it is noticed.
 *
 * <p>Patient-access rows (PHI_READ, PHI_WRITE) are the volume: one per record opened. They go
 * through a bounded queue to a single writer that inserts them in batches, so opening a patient
 * no longer waits on an insert in a second transaction holding a second pooled connection (audit
 * P3). When the queue is full the row is written at once instead: nothing is dropped. Sign-ins,
 * admin changes and data-rights events are written immediately, as before.
 *
 * <p>A row about a record of a patient (a prescription, a consultation's log, a file) that does not
 * name the patient gets it from that record, so "who looked at this patient?" also finds those reads
 * (audit S5).
 *
 * <p>What goes in {@code detail}: names of things that changed and small facts (a role, a count,
 * a reason). Never a password, a token, or the content of a record.
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 500;

    private static final String INSERT = """
            INSERT INTO audit_log (id, occurred_at, practice_id, category, action, actor_id, actor_label, patient_id, target_type, target_id,
                                   method, route, status, ip, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** How to find the patient of a record by its id, per audited area; the clinic is part of the match. */
    static final Map<String, String> PATIENT_OF = Map.ofEntries(
            Map.entry("prescriptions", "SELECT patient_id FROM prescriptions WHERE id = ? AND practice_id = ?"),
            Map.entry("insurance-forms", "SELECT patient_id FROM insurance_forms WHERE id = ? AND practice_id = ?"),
            Map.entry("invoices", "SELECT patient_id FROM invoices WHERE id = ? AND practice_id = ?"),
            Map.entry("consultations", "SELECT patient_id FROM consultations WHERE id = ? AND practice_id = ?"),
            Map.entry("lab-orders", "SELECT patient_id FROM lab_orders WHERE id = ? AND practice_id = ?"),
            Map.entry("appointments", "SELECT patient_id FROM appointments WHERE id = ? AND practice_id = ?"),
            Map.entry("tax-documents", "SELECT patient_id FROM tax_documents WHERE id = ? AND practice_id = ?"),
            Map.entry("cheques", "SELECT patient_id FROM cheques WHERE id = ? AND practice_id = ?"),
            Map.entry("stock/treatment-invoices", "SELECT patient_id FROM treatment_invoices WHERE id = ? AND practice_id = ?"),
            Map.entry("files", """
                    SELECT CASE WHEN f.owner_type IN ('PATIENT_PHOTO', 'PATIENT_DOCUMENT', 'CLINICAL_PHOTO') THEN f.owner_id
                                WHEN f.owner_type = 'PRESCRIPTION' THEN (SELECT patient_id FROM prescriptions WHERE id = f.owner_id)
                                WHEN f.owner_type = 'INSURANCE_FORM' THEN (SELECT patient_id FROM insurance_forms WHERE id = f.owner_id)
                                WHEN f.owner_type = 'LAB_ORDER' THEN (SELECT patient_id FROM lab_orders WHERE id = f.owner_id)
                                WHEN f.owner_type = 'TAX_DOCUMENT' THEN (SELECT patient_id FROM tax_documents WHERE id = f.owner_id)
                           END
                    FROM files f WHERE f.id = ? AND f.practice_id = ?
                    """));

    @Builder
    public record Event(AuditCategory category, String action, UUID practiceId, UUID actorId, String actorLabel, UUID patientId,
                        String targetType, UUID targetId, String method, String route, Integer status, String ip,
                        Map<String, ?> detail) {
    }

    /** An event with everything taken from the context already: what one row of the table holds. */
    private record Row(UUID id, OffsetDateTime at, UUID practiceId, AuditCategory category, String action, UUID actorId, String actorLabel,
                       UUID patientId, String targetType, UUID targetId, String method, String route, Integer status, String ip,
                       String detail) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final boolean queuePatientAccess;
    private final BlockingQueue<Row> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong pending = new AtomicLong();
    private volatile boolean running;
    private Thread writer;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper objectMapper, PlatformTransactionManager transactions,
                    @Value("${orthoflow.audit.queue-patient-access:true}") boolean queuePatientAccess) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.queuePatientAccess = queuePatientAccess;
    }

    @PostConstruct
    void start() {
        if (!queuePatientAccess) {
            return;
        }
        running = true;
        writer = new Thread(this::drainLoop, "audit-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** What is still queued is written before the application stops. */
    @PreDestroy
    void stop() {
        running = false;
        if (writer != null) {
            writer.interrupt();
            try {
                writer.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<Row> rest = new ArrayList<>();
        queue.drainTo(rest);
        writeBatch(rest);
    }

    /** Records an event; anything not given is taken from the signed-in user and the current request. */
    public void record(Event event) {
        try {
            Row row = rowFor(event);
            boolean patientAccess = event.category() == AuditCategory.PHI_READ || event.category() == AuditCategory.PHI_WRITE;
            if (patientAccess && running) {
                pending.incrementAndGet();
                if (queue.offer(row)) {
                    return;
                }
                pending.decrementAndGet();
            }
            tx.executeWithoutResult(s -> insert(List.of(resolvePatient(row))));
        } catch (Exception e) {
            log.error("AUDIT WRITE FAILED for {} {} (patient {}, actor {}): {}", event.category(), event.action(), event.patientId(),
                    event.actorId(), e.toString());
        }
    }

    /** Waits (up to ten seconds) until every queued row is written: for tests, and for anything that reads the log back at once. */
    public void flush() {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (pending.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void drainLoop() {
        List<Row> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                Row first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                writeBatch(batch);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
            } finally {
                pending.addAndGet(-batch.size());
                batch.clear();
            }
        }
    }

    /** Outside any transaction, on purpose: plain JDBC needs no clinic in scope, and each batch commits on its own. */
    private void writeBatch(List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            insert(rows.stream().map(this::resolvePatient).toList());
        } catch (Exception e) {
            log.error("AUDIT WRITE FAILED for a batch of {} patient-access row(s), first {} {} (patient {}): {}", rows.size(),
                    rows.get(0).category(), rows.get(0).action(), rows.get(0).patientId(), e.toString());
        }
    }

    private void insert(List<Row> rows) {
        jdbc.batchUpdate(INSERT, rows, rows.size(), (ps, r) -> {
            ps.setObject(1, r.id());
            ps.setObject(2, r.at());
            ps.setObject(3, r.practiceId());
            ps.setString(4, r.category().name());
            ps.setString(5, r.action());
            ps.setObject(6, r.actorId());
            ps.setString(7, r.actorLabel());
            ps.setObject(8, r.patientId());
            ps.setString(9, r.targetType());
            ps.setObject(10, r.targetId());
            ps.setString(11, r.method());
            ps.setString(12, r.route());
            ps.setObject(13, r.status() == null ? null : r.status().shortValue(), java.sql.Types.SMALLINT);
            ps.setString(14, r.ip());
            ps.setString(15, r.detail());
        });
    }

    private Row rowFor(Event event) throws Exception {
        AuthenticatedUser user = signedInUser();
        UUID practiceId = event.practiceId() != null ? event.practiceId() : user == null ? null : user.practiceId();
        UUID actorId = event.actorId() != null ? event.actorId() : user == null ? null : user.id();
        String actorLabel = event.actorLabel() != null ? event.actorLabel() : user == null ? null : user.email();
        HttpServletRequest request = currentRequest();
        String ip = event.ip() != null ? event.ip() : request == null ? null : clientIp(request);
        String json = event.detail() == null || event.detail().isEmpty() ? null : objectMapper.writeValueAsString(event.detail());
        return new Row(UUID.randomUUID(), OffsetDateTime.now(), practiceId, event.category(), event.action(), actorId, truncate(actorLabel, 255),
                event.patientId(), event.targetType(), event.targetId(), event.method(), truncate(event.route(), 255), event.status(),
                truncate(ip, 64), json);
    }

    private Row resolvePatient(Row row) {
        String sql = row.targetType() == null ? null : PATIENT_OF.get(row.targetType());
        if (row.patientId() != null || row.targetId() == null || row.practiceId() == null || sql == null) {
            return row;
        }
        try {
            List<UUID> found = jdbc.queryForList(sql, UUID.class, row.targetId(), row.practiceId());
            UUID patient = found.isEmpty() ? null : found.get(0);
            return patient == null ? row : new Row(row.id(), row.at(), row.practiceId(), row.category(), row.action(), row.actorId(),
                    row.actorLabel(), patient, row.targetType(), row.targetId(), row.method(), row.route(), row.status(), row.ip(), row.detail());
        } catch (RuntimeException e) {
            // A record already gone, or a type with no patient: the row is kept without one.
            return row;
        }
    }

    /** Shorthand for an event with a few details. */
    public void record(AuditCategory category, String action, UUID patientId, Map<String, ?> detail) {
        record(Event.builder().category(category).action(action).patientId(patientId).detail(detail).build());
    }

    /** The address the request came from (see {@link ClientAddress}). */
    public static String clientIp(HttpServletRequest request) {
        return ClientAddress.of(request);
    }

    private static AuthenticatedUser signedInUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes ? attributes.getRequest() : null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
