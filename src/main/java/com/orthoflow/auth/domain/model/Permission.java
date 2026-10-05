package com.orthoflow.auth.domain.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.orthoflow.auth.domain.model.UserRole.ADMIN;
import static com.orthoflow.auth.domain.model.UserRole.ASSISTANT;
import static com.orthoflow.auth.domain.model.UserRole.DOCTOR;

/**
 * What a signed-in person may do, finer than the three fixed roles (ADR 0008).
 * The set is defined here, in code, because endpoints reference it by name; an
 * admin edits only which role holds which permission.
 *
 * <p>The URL matrix in {@code SecurityConfig} still draws the broad role floor;
 * these are the finer gates behind it, checked as authorities
 * ({@code hasAuthority('FINANCE_VIEW')}) and mirrored to the frontend so it can
 * hide what the server would refuse.
 *
 * <p>An assistant sees no financial totals by default, which is the one
 * deliberate difference between the DOCTOR and ASSISTANT defaults on the money
 * side: they can take a payment, they cannot read the practice's takings.
 */
public enum Permission {
    PATIENT_READ,
    PATIENT_WRITE,
    PATIENT_DELETE,
    PATIENT_MERGE,
    CLINICAL_READ,
    CLINICAL_WRITE,
    AGENDA_VIEW,
    AGENDA_MANAGE,
    WAITING_ROOM_MANAGE,
    BILLING_READ,
    BILLING_WRITE,
    /** Dashboards, reports and anything that shows the practice's totals. */
    FINANCE_VIEW,
    /** Cash closing, cheque lifecycle, instalment plans, tax documents. */
    FINANCE_MANAGE,
    EXPENSES_MANAGE,
    RETROCESSION_VIEW,
    RETROCESSION_MANAGE,
    STOCK_READ,
    STOCK_WRITE,
    LAB_ORDERS_MANAGE,
    TASKS_MANAGE,
    /** Seeing every member's tasks, not only one's own. */
    TASKS_ADMIN,
    MESSAGING_SEND,
    MESSAGING_VIEW,
    BOOKING_REVIEW,
    SURVEYS_VIEW,
    ANALYTICS_VIEW,
    STERILIZATION_MANAGE,
    SETTINGS_MANAGE,
    USERS_MANAGE;

    private static final Map<UserRole, Set<Permission>> DEFAULTS = new EnumMap<>(UserRole.class);

    static {
        DEFAULTS.put(ADMIN, EnumSet.allOf(Permission.class));
        DEFAULTS.put(DOCTOR, EnumSet.of(
                PATIENT_READ, PATIENT_WRITE, CLINICAL_READ, CLINICAL_WRITE,
                AGENDA_VIEW, AGENDA_MANAGE, WAITING_ROOM_MANAGE,
                BILLING_READ, BILLING_WRITE, FINANCE_VIEW, FINANCE_MANAGE,
                RETROCESSION_VIEW,
                STOCK_READ, STOCK_WRITE, LAB_ORDERS_MANAGE, TASKS_MANAGE,
                MESSAGING_SEND, MESSAGING_VIEW, BOOKING_REVIEW, SURVEYS_VIEW, ANALYTICS_VIEW,
                STERILIZATION_MANAGE));
        DEFAULTS.put(ASSISTANT, EnumSet.of(
                PATIENT_READ, PATIENT_WRITE,
                AGENDA_VIEW, AGENDA_MANAGE, WAITING_ROOM_MANAGE,
                BILLING_READ, BILLING_WRITE,
                STOCK_READ, STOCK_WRITE, LAB_ORDERS_MANAGE, TASKS_MANAGE,
                MESSAGING_SEND, MESSAGING_VIEW, BOOKING_REVIEW,
                STERILIZATION_MANAGE));
    }

    /** What a role holds until an admin customises it. Mutable copy; never null. */
    public static Set<Permission> defaultsFor(UserRole role) {
        return EnumSet.copyOf(DEFAULTS.get(role));
    }
}
