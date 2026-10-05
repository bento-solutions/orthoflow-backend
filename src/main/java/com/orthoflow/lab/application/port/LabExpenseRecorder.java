package com.orthoflow.lab.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Books the lab's fee as an expense when a piece arrives; finance implements it. Idempotent per order. */
public interface LabExpenseRecorder {

    void recordLabFee(UUID practiceId, UUID labOrderId, String labName, BigDecimal amount, LocalDate receivedOn, UUID createdBy);
}
