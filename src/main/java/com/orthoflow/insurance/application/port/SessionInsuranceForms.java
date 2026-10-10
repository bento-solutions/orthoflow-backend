package com.orthoflow.insurance.application.port;

import com.orthoflow.insurance.application.dto.InsuranceFormDtos.Issued;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The published port a saved consultation uses to hand the patient's insurance paperwork
 * to the front desk, without the consultation module knowing how forms are drawn.
 */
public interface SessionInsuranceForms {

    /** An act of the session's plan: done today ({@code performed}) or proposed. */
    record SessionAct(UUID treatmentId, String label, String teeth, BigDecimal amount, boolean performed) {
    }

    /**
     * The forms the session calls for, filled and sent to the front desk: one reporting the
     * acts done today, one asking prior agreement for the proposed acts that need it
     * (orthodontics, prostheses). Nothing when the patient has no insurer or nothing qualifies.
     */
    List<Issued> afterSession(UUID practiceId, UUID actorId, UUID consultationId, UUID patientId, List<SessionAct> acts);
}
