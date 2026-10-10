package com.orthoflow.storage.domain.model;

import com.orthoflow.auth.domain.model.Permission;

/**
 * What a file is attached to, and therefore who may put one there or read it.
 * An upload endpoint is only as safe as this table: the file inherits the
 * permission of the record it belongs to, so a receptionist cannot read
 * expense receipts by guessing a file id.
 */
public enum FileOwnerType {
    PATIENT_PHOTO(Permission.PATIENT_READ, Permission.PATIENT_WRITE, 5),
    PATIENT_DOCUMENT(Permission.CLINICAL_READ, Permission.CLINICAL_WRITE, 15),
    PRACTICE_LOGO(Permission.AGENDA_VIEW, Permission.SETTINGS_MANAGE, 2),
    EXPENSE_RECEIPT(Permission.FINANCE_VIEW, Permission.EXPENSES_MANAGE, 10),
    LAB_ORDER(Permission.LAB_ORDERS_MANAGE, Permission.LAB_ORDERS_MANAGE, 15),
    MESSAGE_ATTACHMENT(Permission.MESSAGING_VIEW, Permission.MESSAGING_SEND, 10),
    TAX_DOCUMENT(Permission.BILLING_READ, Permission.FINANCE_MANAGE, 5),
    INSURANCE_FORM(Permission.BILLING_READ, Permission.BILLING_WRITE, 15),
    PRESCRIPTION(Permission.CLINICAL_READ, Permission.CLINICAL_WRITE, 5),
    /** Orthodontic photos and radiographs, owned by the patient (imaging module). */
    CLINICAL_PHOTO(Permission.CLINICAL_READ, Permission.CLINICAL_WRITE, 15);

    private final Permission readPermission;
    private final Permission writePermission;
    private final int maxMegabytes;

    FileOwnerType(Permission readPermission, Permission writePermission, int maxMegabytes) {
        this.readPermission = readPermission;
        this.writePermission = writePermission;
        this.maxMegabytes = maxMegabytes;
    }

    public Permission readPermission() {
        return readPermission;
    }

    public Permission writePermission() {
        return writePermission;
    }

    public long maxBytes() {
        return maxMegabytes * 1024L * 1024L;
    }
}
