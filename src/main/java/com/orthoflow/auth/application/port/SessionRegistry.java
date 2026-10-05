package com.orthoflow.auth.application.port;

import java.util.UUID;

/**
 * Whether the sign-in a token belongs to is still live. A token without a
 * session id (issued before sessions were tracked) is accepted, so deploying
 * this does not sign everyone out.
 */
public interface SessionRegistry {

    /** True unless the session exists and was revoked. Also records activity. */
    boolean isLive(UUID sessionId);
}
