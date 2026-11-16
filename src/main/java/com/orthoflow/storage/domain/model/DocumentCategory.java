package com.orthoflow.storage.domain.model;

/**
 * What a patient document is, chosen when it is uploaded. The patient file shows the
 * latest {@link #XRAY} and {@link #INTRAORAL_3D} beside the 3D chart; {@link #IMAGE}
 * is anything else the doctor adds, shown in a gallery under them. An intraoral scan or an image
 * may also be a 3D model (STL, PLY, OBJ, 3MF or glTF), which the patient file shows in a viewer;
 * an X-ray is always a picture. Null on files uploaded before categories existed, and on every
 * owner type other than a patient document.
 */
public enum DocumentCategory {
    XRAY,
    INTRAORAL_3D,
    IMAGE
}
