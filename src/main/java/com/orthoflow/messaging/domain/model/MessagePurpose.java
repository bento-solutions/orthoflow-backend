package com.orthoflow.messaging.domain.model;

/** Why a message exists. Templates are chosen by (channel, purpose, language). */
public enum MessagePurpose {
    APPOINTMENT_REMINDER,
    APPOINTMENT_CONFIRMED,
    APPOINTMENT_CANCELLED,
    INSTALMENT_REMINDER,
    RECALL,
    SURVEY_REQUEST,
    BOOKING_RECEIVED,
    BOOKING_CONFIRMED,
    BOOKING_DECLINED,
    REGISTRATION_INVITE,
    STAFF_INVITE,
    PASSWORD_RESET,
    LAB_ORDER_RECEIVED,
    CHEQUE_REJECTED,
    TASK_ASSIGNED,
    INTERNAL_MESSAGE,
    TEST,
    GENERIC
}
