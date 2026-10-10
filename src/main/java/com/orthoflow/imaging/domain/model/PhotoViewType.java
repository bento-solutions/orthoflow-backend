package com.orthoflow.imaging.domain.model;

/**
 * The standard orthodontic record: three views of the face, the two arches seen
 * from above and below, the bite from the left, the front and the right, and the
 * two radiographs a diagnosis starts from.
 */
public enum PhotoViewType {
    SMILE,
    FACE_AT_REST,
    PROFILE,
    UPPER_OCCLUSAL,
    LOWER_OCCLUSAL,
    LEFT_LATERAL,
    FRONTAL_OCCLUSION,
    RIGHT_LATERAL,
    PANORAMIC_XRAY,
    LATERAL_CEPHALOGRAM
}
