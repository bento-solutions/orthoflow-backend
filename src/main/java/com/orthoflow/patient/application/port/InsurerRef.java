package com.orthoflow.patient.application.port;

import java.util.UUID;

/** An insurer as other modules see it: enough to pick and title the paper form its patients need. */
public record InsurerRef(UUID id, String code, String name, String kind, String formCode) {
}
