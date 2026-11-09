package com.orthoflow.compliance.application.service;

import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which stored files are a patient's. A file is filed under the record it was attached to, and that
 * record is not always the patient: a prescription PDF is filed under the prescription, a care form
 * under the form, a lab slip under the order. Matching {@code owner_id = patientId} alone left those
 * behind after an erasure (with the name, the drugs and the acts in them) and out of the export.
 *
 * <p>The decision is a {@code switch} over every {@link FileOwnerType}, so a type added later does not
 * compile until someone says whose files it holds.
 */
@Component
@RequiredArgsConstructor
public class PatientFiles {

    private final UnlinkedCopies unlinked;
    private final FileService fileService;

    /**
     * The ids that files of this type are filed under when they are the patient's ({@code :p}), as SQL,
     * or {@code null} for a type that never holds patient data. The patient's own types include the
     * records merged into them.
     */
    static String ownersOf(FileOwnerType type) {
        return switch (type) {
            case PATIENT_PHOTO, PATIENT_DOCUMENT, CLINICAL_PHOTO -> "SELECT id FROM patients WHERE id = :p OR merged_into_id = :p";
            case PRESCRIPTION -> "SELECT id FROM prescriptions WHERE patient_id = :p";
            case INSURANCE_FORM -> "SELECT id FROM insurance_forms WHERE patient_id = :p";
            case LAB_ORDER -> "SELECT id FROM lab_orders WHERE patient_id = :p";
            // Nothing attaches files to messages yet; when something does, it attaches them to the outbox row.
            case MESSAGE_ATTACHMENT -> "SELECT id FROM message_outbox WHERE patient_id = :p";
            // Fee notes and care sheets: financial records the law keeps, so only anonymisation touches them.
            case TAX_DOCUMENT -> "SELECT id FROM tax_documents WHERE patient_id = :p";
            case PRACTICE_LOGO, EXPENSE_RECEIPT -> null;
        };
    }

    /** Files that stay with a retained financial record (anonymisation replaces them, erasure is refused while they exist). */
    static boolean retained(FileOwnerType type) {
        return type == FileOwnerType.TAX_DOCUMENT;
    }

    /** The files rows found for one patient: their ids and where their bytes are. */
    public record Found(List<UUID> ids, List<String> storageKeys) {
        public boolean isEmpty() {
            return ids.isEmpty();
        }
    }

    /** A SQL condition on {@code files f} matching the patient's files of these types. */
    static String condition(List<FileOwnerType> types) {
        return types.stream()
                .map(t -> "(f.owner_type = '" + t.name() + "' AND f.owner_id IN (" + ownersOf(t) + "))")
                .collect(Collectors.joining(" OR ", "(", ")"));
    }

    static List<FileOwnerType> patientTypes(boolean includeRetained) {
        return Arrays.stream(FileOwnerType.values())
                .filter(t -> ownersOf(t) != null)
                .filter(t -> includeRetained || !retained(t))
                .toList();
    }

    /** Every live file of the patient, retained ones included, for the export: who and what, never the storage path. */
    public String exportQuery() {
        return "SELECT (to_jsonb(f) - 'storage_key')::text FROM files f WHERE f.deleted_at IS NULL AND "
                + condition(patientTypes(true)) + " ORDER BY f.created_at";
    }

    /**
     * Finds the patient's files. It has to run before the rows they are filed under are deleted,
     * since those rows are how a prescription's PDF is known to be this patient's.
     * Soft-deleted rows are included: their bytes are gone but the name of the file is not.
     */
    public Found find(UUID patientId, boolean includeRetained) {
        List<UUID> ids = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        unlinked.named().query("SELECT f.id, f.storage_key FROM files f WHERE " + condition(patientTypes(includeRetained)),
                new MapSqlParameterSource("p", patientId), rs -> {
                    ids.add(rs.getObject("id", UUID.class));
                    keys.add(rs.getString("storage_key"));
                });
        return new Found(List.copyOf(ids), List.copyOf(keys));
    }

    /**
     * Deletes the rows (whatever pointed at them must be gone or cleared by now) and the bytes once
     * the transaction commits, so an erasure that rolls back loses nothing.
     */
    public void delete(Found found) {
        if (found.isEmpty()) {
            return;
        }
        unlinked.named().update("DELETE FROM files WHERE id IN (:ids)", new MapSqlParameterSource("ids", found.ids()));
        fileService.deleteBytesAfterCommit(found.storageKeys());
    }
}
