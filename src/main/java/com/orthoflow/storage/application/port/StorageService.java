package com.orthoflow.storage.application.port;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where the bytes live. A local volume today; an S3-compatible bucket (MinIO)
 * later, behind this same interface. Callers never see a path or a URL — files
 * are always served through an authenticated endpoint.
 */
public interface StorageService {

    void put(String key, byte[] bytes) throws IOException;

    InputStream open(String key) throws IOException;

    void delete(String key) throws IOException;
}
