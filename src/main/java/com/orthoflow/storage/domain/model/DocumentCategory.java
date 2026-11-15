package com.orthoflow.storage.domain.model;

/**
 * What a patient document is. Only a {@link FileOwnerType#PATIENT_DOCUMENT} carries one,
 * and all three are pictures: the dossier shows the latest X-ray and intraoral scan beside
 * the 3D chart, so a PDF in one of them would be a hole in that view.
 */
public enum DocumentCategory {
    /** A radiograph, usually the panoramic. */
    XRAY,
    /** A picture of the patient's intraoral 3D scan. */
    INTRAORAL_3D,
    /** Any other picture. */
    IMAGE
}
