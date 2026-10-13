package com.orthoflow.audit.application.service;

/** What kind of thing an {@link AuditLog} row records. */
public enum AuditCategory {
    /** Sign-in, failed sign-in, password reset and change. */
    AUTH,
    /** Users, roles and permissions, clinic settings. */
    ADMIN,
    /** A patient's rights exercised: export, erasure, anonymisation. */
    DATA_RIGHTS,
    /** A patient's record viewed or downloaded. */
    PHI_READ,
    /** A patient's record created, changed or deleted. */
    PHI_WRITE
}
