package com.orthoflow.storage.domain.model;

/**
 * What a patient document is, chosen when it is uploaded. The patient file shows the
 * latest {@link #XRAY} and {@link #INTRAORAL_3D} beside the 3D chart; {@link #IMAGE}
 * is any other picture. An intraoral scan may also be the scan itself, as a 3D model (STL, PLY,
 * OBJ, 3MF or glTF), which the patient file shows in a viewer. Null on files uploaded before categories existed, and on
 * every owner type other than a patient document.
 */
public enum DocumentCategory {
    XRAY,
    INTRAORAL_3D,
    IMAGE
}
