package com.orthoflow.retrocession.domain.model;

/** What a collaborator's percentage is taken from. */
public enum Basis {
    /** Money actually received from patients, on the day it was allocated to an invoice. */
    COLLECTED,
    /** What was invoiced, on the invoice's issue date, whether or not it has been paid yet. */
    PRODUCED
}
