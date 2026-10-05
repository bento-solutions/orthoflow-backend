package com.orthoflow.scheduling.infrastructure.adapter.persistence;

import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AppointmentJpaRepository extends JpaRepository<Appointment, UUID> {

    List<Appointment> findByPatientId(UUID patientId);

    /**
     * Loading the entire appointment history to render a single day's
     * schedule was the standout example in the audit's performance findings
     * (II.8/VI.4) — every screen filtered client-side after fetching every
     * appointment the clinic has ever had. No JOIN FETCH needed any more:
     * patient is a plain UUID column, not a relation, so there's no N+1 to
     * avoid — the batched PatientLookup#findSummaries call in
     * AppointmentService does the equivalent in one query per response set.
     */
    List<Appointment> findByDateTimeBetween(OffsetDateTime start, OffsetDateTime end);

    @Query("SELECT a FROM Appointment a ORDER BY a.dateTime")
    List<Appointment> findAllOrderedByDateTime();

    /** The agenda: a window, optionally narrowed to one practitioner, chair, status or type. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.practiceId = :practiceId AND a.dateTime >= :from AND a.dateTime < :to
              AND (:practitionerId IS NULL OR a.practitionerId = :practitionerId)
              AND (:chairId IS NULL OR a.chairId = :chairId)
              AND (:status IS NULL OR a.status = :status)
              AND (:typeId IS NULL OR a.appointmentTypeId = :typeId)
            ORDER BY a.dateTime
            """)
    List<Appointment> agenda(@Param("practiceId") UUID practiceId, @Param("from") OffsetDateTime from,
                             @Param("to") OffsetDateTime to, @Param("practitionerId") UUID practitionerId,
                             @Param("chairId") UUID chairId, @Param("status") AppointmentStatus status,
                             @Param("typeId") UUID typeId);

    /** Today's live board: everyone who has arrived and not been called yet, in calling order. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.practiceId = :practiceId AND a.status = com.orthoflow.scheduling.domain.model.AppointmentStatus.ARRIVED
            ORDER BY a.waitingPriority, a.arrivedAt
            """)
    List<Appointment> waiting(@Param("practiceId") UUID practiceId);

    @Query("""
            SELECT a FROM Appointment a
            WHERE a.practiceId = :practiceId AND a.status = com.orthoflow.scheduling.domain.model.AppointmentStatus.IN_CHAIR
            ORDER BY a.seatedAt
            """)
    List<Appointment> inChair(@Param("practiceId") UUID practiceId);

    /** Visits whose arrival fell in the window, for wait-time statistics. */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.practiceId = :practiceId AND a.arrivedAt >= :from AND a.arrivedAt < :to
            """)
    List<Appointment> arrivedBetween(@Param("practiceId") UUID practiceId, @Param("from") OffsetDateTime from,
                                     @Param("to") OffsetDateTime to);

    @Query("""
            SELECT a FROM Appointment a
            WHERE a.practiceId = :practiceId AND a.status IN :statuses AND a.dateTime >= :from AND a.dateTime < :to
            ORDER BY a.dateTime
            """)
    List<Appointment> inStatuses(@Param("practiceId") UUID practiceId,
                                 @Param("statuses") java.util.Collection<AppointmentStatus> statuses,
                                 @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
