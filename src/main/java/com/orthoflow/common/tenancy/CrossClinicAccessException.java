package com.orthoflow.common.tenancy;

import com.orthoflow.common.exception.NotFoundException;

/**
 * A row of another clinic was loaded by id. To the caller it is a 404, exactly as if
 * the id did not exist: saying "forbidden" would confirm that it does.
 */
public class CrossClinicAccessException extends NotFoundException {

    public CrossClinicAccessException(String entity) {
        super(entity + " not found");
    }
}
