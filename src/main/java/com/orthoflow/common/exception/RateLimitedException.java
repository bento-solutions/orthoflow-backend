package com.orthoflow.common.exception;

/** The caller is asking too often; the handler answers 429. */
public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message) {
        super(message);
    }
}
