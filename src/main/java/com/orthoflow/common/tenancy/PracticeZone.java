package com.orthoflow.common.tenancy;

import java.time.ZoneId;
import java.util.UUID;

/**
 * The clinic's own time zone. "Today", "this month" and "the day before the
 * appointment" are the clinic's days, not the server's, so anything that cuts
 * time into days asks here.
 */
public interface PracticeZone {

    ZoneId of(UUID practiceId);
}
