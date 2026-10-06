package com.orthoflow.registration.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.messaging.application.service.ConsentService;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientMatcher;
import com.orthoflow.patient.application.port.PatientRegistrar;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLink;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.settings.application.service.PracticeProfileService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Self-registration: the patient fills in their own file before they arrive. It
 * lands in a PENDING list, never straight in the patient table — a form on the
 * internet is not allowed to create records — and staff approve it with the
 * duplicate check beside it, either as a new patient or into an existing file.
 * The consent ticked on the form (Law 09-08) is what gets stored with the patient.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    @io.swagger.v3.oas.annotations.media.Schema(name = "RegistrationPublicInfo")

    public record PublicInfo(String clinicName, String phone, String defaultLanguage) {
    }

    public record Form(@NotBlank @Size(max = 255) String firstName, @NotBlank @Size(max = 255) String lastName,
                       @Pattern(regexp = "[MFmf]") String gender, @Past LocalDate dateOfBirth, @Size(max = 50) String phone,
                       @Email @Size(max = 255) String email, @Size(max = 2000) String address, @Size(max = 50) String cin,
                       @Size(max = 255) String guardianName, @Size(max = 50) String guardianPhone,
                       @Size(max = 100) String insuranceProvider, @Size(max = 100) String insuranceNumber,
                       @Size(max = 150) String occupation, @Pattern(regexp = "fr|en|ar") String language,
                       @AssertTrue(message = "consent is required") boolean consent, boolean consentWhatsapp, boolean consentEmail,
                       /** A hidden field a person never fills; a bot does. */ String website) {
    }

    public record Pending(UUID id, String firstName, String lastName, String gender, LocalDate dateOfBirth, String phone, String email,
                          String address, String cin, String guardianName, String guardianPhone, String insuranceProvider,
                          String insuranceNumber, String occupation, String language, OffsetDateTime createdAt, UUID invitedPatientId,
                          String status, List<PatientMatcher.Candidate> possibleDuplicates) {
    }

    public record Approve(UUID mergeIntoPatientId) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "RegistrationInvite")

    public record Invite(String url, boolean sent) {
    }

    private final JdbcTemplate jdbc;
    private final PublicLinkService publicLinks;
    private final PracticeProfileService profiles;
    private final PatientRegistrar registrar;
    private final PatientMatcher matcher;
    private final PatientLookup patientLookup;
    private final ConsentService consent;
    private final StaffNotifier notifier;
    private final LiveEventPublisher liveEvents;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    @Transactional(readOnly = true)
    public PublicInfo info(String token) {
        var link = publicLinks.resolve(token, PublicLinkPurpose.REGISTRATION);
        var clinic = profiles.require(link.getPracticeId());
        return new PublicInfo(clinic.getName(), clinic.getPhone(), clinic.getDefaultLanguage());
    }

    /** Accepts a form. A honeypot hit is answered like a success and stored nowhere; a used-up invitation looks like a bad link. */
    @Transactional
    public void submit(String token, Form f) {
        PublicLink link = publicLinks.resolve(token, PublicLinkPurpose.REGISTRATION);
        if (f.website() != null && !f.website().isBlank()) {
            return;
        }
        if (PublicLink.SHARED.equals(link.getSubjectType()) == false && !publicLinks.consume(link.getId())) {
            throw new NotFoundException("This link is not valid");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pending_patients (id, practice_id, first_name, last_name, gender, date_of_birth, phone, email, address, cin, guardian_name,
                    guardian_phone, insurance_provider, insurance_number, occupation, preferred_language, consented_at, consent_whatsapp, consent_email, invited_patient_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?)
                """, id, link.getPracticeId(), f.firstName().trim(), f.lastName().trim(), f.gender() == null ? null : f.gender().toUpperCase(),
                f.dateOfBirth(), blankToNull(f.phone()), blankToNull(f.email()), blankToNull(f.address()), blankToNull(f.cin()),
                blankToNull(f.guardianName()), blankToNull(f.guardianPhone()), blankToNull(f.insuranceProvider()), blankToNull(f.insuranceNumber()),
                blankToNull(f.occupation()), f.language() == null ? "fr" : f.language(), f.consentWhatsapp(), f.consentEmail(),
                "PATIENT".equals(link.getSubjectType()) ? link.getSubjectId() : null);
        notifier.toPermission(link.getPracticeId(), Permission.BOOKING_REVIEW, MessagePurpose.GENERIC, "Nouvelle fiche patient à valider",
                f.firstName().trim() + " " + f.lastName().trim() + " a rempli sa fiche en ligne.", "PENDING_PATIENT", id);
        liveEvents.publish(link.getPracticeId(), "registration", id);
    }

    @Transactional(readOnly = true)
    public List<Pending> pending(UUID practiceId) {
        return jdbc.query("SELECT * FROM pending_patients WHERE practice_id = ? AND status = 'PENDING' ORDER BY created_at", (rs, i) -> map(rs, practiceId), practiceId);
    }

    @Transactional(readOnly = true)
    public long pendingCount(UUID practiceId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM pending_patients WHERE practice_id = ? AND status = 'PENDING'", Long.class, practiceId);
        return n == null ? 0 : n;
    }

    @Transactional
    public UUID approve(UUID practiceId, UUID actorId, UUID id, Approve a) {
        Map<String, Object> row = require(practiceId, id);
        PatientRegistrar.Registration registration = new PatientRegistrar.Registration((String) row.get("first_name"), (String) row.get("last_name"),
                (String) row.get("gender"), date(row.get("date_of_birth")), (String) row.get("phone"), (String) row.get("email"), (String) row.get("address"),
                (String) row.get("cin"), (String) row.get("guardian_name"), (String) row.get("guardian_phone"), (String) row.get("insurance_provider"),
                (String) row.get("insurance_number"), (String) row.get("occupation"), (String) row.get("preferred_language"), OffsetDateTime.now());
        UUID patientId;
        if (a != null && a.mergeIntoPatientId() != null) {
            if (!patientLookup.exists(a.mergeIntoPatientId())) {
                throw new NotFoundException("Patient not found");
            }
            registrar.enrich(a.mergeIntoPatientId(), registration);
            patientId = a.mergeIntoPatientId();
        } else if (row.get("invited_patient_id") != null) {
            patientId = (UUID) row.get("invited_patient_id");
            registrar.enrich(patientId, registration);
        } else {
            patientId = registrar.register(registration);
        }
        if (Boolean.TRUE.equals(row.get("consent_whatsapp"))) consent.record(patientId, MessageChannel.WHATSAPP, true, "SELF_REGISTRATION");
        if (Boolean.TRUE.equals(row.get("consent_email"))) consent.record(patientId, MessageChannel.EMAIL, true, "SELF_REGISTRATION");
        jdbc.update("UPDATE pending_patients SET status = 'APPROVED', patient_id = ?, decided_by = ?, decided_at = NOW() WHERE id = ?", patientId, actorId, id);
        liveEvents.publish(practiceId, "registration", id);
        return patientId;
    }

    @Transactional
    public void reject(UUID practiceId, UUID actorId, UUID id, String reason) {
        require(practiceId, id);
        jdbc.update("UPDATE pending_patients SET status = 'REJECTED', reject_reason = ?, decided_by = ?, decided_at = NOW() WHERE id = ?", blankToNull(reason), actorId, id);
        liveEvents.publish(practiceId, "registration", id);
    }

    /** A link for one patient to complete their own file: single use, two weeks. */
    @Transactional
    public Invite invite(UUID practiceId, UUID actorId, UUID patientId) {
        if (!patientLookup.exists(patientId)) {
            throw new NotFoundException("Patient not found");
        }
        var issued = publicLinks.issue(practiceId, PublicLinkPurpose.REGISTRATION, "PATIENT", patientId, Duration.ofDays(14), 1, actorId);
        return new Invite(frontendUrl + "/public/register/" + issued.token(), false);
    }

    private Pending map(java.sql.ResultSet rs, UUID practiceId) throws java.sql.SQLException {
        LocalDate dob = rs.getObject("date_of_birth", LocalDate.class);
        return new Pending(rs.getObject("id", UUID.class), rs.getString("first_name"), rs.getString("last_name"), rs.getString("gender"), dob,
                rs.getString("phone"), rs.getString("email"), rs.getString("address"), rs.getString("cin"), rs.getString("guardian_name"),
                rs.getString("guardian_phone"), rs.getString("insurance_provider"), rs.getString("insurance_number"), rs.getString("occupation"),
                rs.getString("preferred_language"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("invited_patient_id", UUID.class),
                rs.getString("status"), matcher.candidates(practiceId, rs.getString("first_name"), rs.getString("last_name"), dob,
                        rs.getString("phone"), rs.getString("cin")));
    }

    private Map<String, Object> require(UUID practiceId, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM pending_patients WHERE id = ? AND practice_id = ?", id, practiceId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Registration not found");
        }
        if (!"PENDING".equals(rows.get(0).get("status"))) {
            throw new ConflictException("This registration was already " + String.valueOf(rows.get(0).get("status")).toLowerCase());
        }
        return rows.get(0);
    }

    private static LocalDate date(Object o) {
        return o == null ? null : ((java.sql.Date) o).toLocalDate();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
