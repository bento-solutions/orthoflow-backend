package com.orthoflow.compliance.application.dto;

import com.orthoflow.billing.domain.model.Invoice;
import com.orthoflow.clinical.application.dto.PatientClinicalRecordResponse;
import com.orthoflow.clinical.application.dto.PerioChartDtos;
import com.orthoflow.clinical.application.dto.PeriodontalStatusResponse;
import com.orthoflow.consultation.application.dto.ConsultationResponse;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.treatment.domain.model.PatientTreatment;
import com.orthoflow.voice.application.dto.VoiceCommandAuditResponse;
import com.orthoflow.voice.application.dto.VoiceSessionResponse;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The full export of one patient's data across every module that holds a
 * record of them — the GDPR Art. 15 (right of access) / Art. 20 (data
 * portability) response, and the shape a data-subject erasure or rectification
 * request is checked against before acting. There was previously no way to
 * produce this at all (audit V.8): fulfilling a real data-subject request
 * meant manually querying four different tables.
 *
 * Deliberately a plain read-side aggregate assembled by
 * {@code DataExportService}, not a persisted entity — it exists only to be
 * serialized to the requester, never stored.
 */
@Getter
@Builder
public class PatientDataExport {
    private OffsetDateTime exportedAt;
    private Patient patient;
    private List<Appointment> appointments;
    private List<PatientTreatment> treatments;
    private List<Invoice> invoices;
    /** Findings, notes (including dictated consultation reports), allergies and history. */
    private PatientClinicalRecordResponse clinicalRecord;
    /** Gum state by area of the mouth, current and earlier. */
    private PeriodontalStatusResponse periodontalStatus;
    /** Six-point probing charts, every measurement of every examination. */
    private List<PerioChartDtos.Chart> periodontalCharts;
    /** Dictated examinations: when, by whom, and the narrative that was signed. */
    private List<VoiceSessionResponse> voiceSessions;
    /** What was dictated and what it was understood to mean. */
    private List<VoiceCommandAuditResponse> voiceCommands;
    /**
     * Recorded consultations, with whatever transcript and draft each still
     * holds (an open one always does; a saved one only where retention is on)
     * and what the doctor validated.
     */
    private List<ConsultationResponse> consultations;
    /**
     * The complete copy: every table that holds something about the patient, row by row as stored
     * (prescriptions, insurance forms, receipts, plans, cheques, photos' records, messages, tasks,
     * surveys, the chart down to each tooth, invoice lines and everything else). The sections above are
     * a readable view of part of this; this is all of it, and it grows with the schema by itself.
     */
    private Map<String, List<Map<String, Object>>> records;
    /** The stored files (photos, scans, documents): what each is and when it was added. The content is downloaded through the files endpoint. */
    private List<Map<String, Object>> files;
    /**
     * Booking requests, registration forms, WhatsApp messages and landing-page contacts from this person that
     * were never linked to their record (matched by name together with phone or e-mail).
     */
    private Map<String, List<Map<String, Object>>> copiesWithoutLink;
}
