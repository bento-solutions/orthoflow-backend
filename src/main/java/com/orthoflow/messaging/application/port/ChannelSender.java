package com.orthoflow.messaging.application.port;

import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.OutboxMessage;

/** Delivers one queued message over one channel. */
public interface ChannelSender {

    MessageChannel channel();

    /** False when the operator has not configured the channel; its messages are cancelled with that reason, not retried. */
    boolean enabled();

    Result send(OutboxMessage message);

    /**
     * @param retryable   whether trying again later could succeed
     * @param retryAfterMs a delay the provider asked for, or 0
     */
    record Result(boolean ok, boolean retryable, String providerMessageId, String error, long retryAfterMs) {

        public static Result sent(String providerMessageId) {
            return new Result(true, false, providerMessageId, null, 0);
        }

        public static Result retry(String error, long retryAfterMs) {
            return new Result(false, true, null, error, retryAfterMs);
        }

        public static Result fail(String error) {
            return new Result(false, false, null, error, 0);
        }
    }
}
