package com.orthoflow.messaging.domain.model;

public enum MessageStatus {
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    CANCELLED;

    /** A message that has left, and so cannot be retried or cancelled by hand. */
    public boolean isDelivered() {
        return this == SENT || this == DELIVERED || this == READ;
    }
}
