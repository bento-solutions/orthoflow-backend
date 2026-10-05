package com.orthoflow.common.events;

import java.util.UUID;

/**
 * "Something changed" for every open screen of a clinic. A port so feature
 * modules can announce a change without depending on how it is delivered
 * (today SSE, in {@code platform.events}). Carries a type and a record id,
 * never patient data: clients refetch through the permission-checked API.
 */
public interface LiveEventPublisher {

    void publish(UUID practiceId, String type, UUID entityId);
}
