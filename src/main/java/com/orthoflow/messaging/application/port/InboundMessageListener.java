package com.orthoflow.messaging.application.port;

import java.util.UUID;

/**
 * A feature that reads patients' replies — appointment confirmation by
 * answering "1" or "2" is the first. Listeners run in order; the first to
 * return true has handled the reply and it leaves the staff inbox.
 */
public interface InboundMessageListener {

    boolean onInbound(UUID practiceId, UUID patientId, String phoneDigits, String body);
}
