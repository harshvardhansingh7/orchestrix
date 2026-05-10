package com.orchestrix.mode;

/**
 * What actually served the response, after the {@link ExecutionMode} resolved.
 * Surfaced in API responses, logs, and metrics so observers can see the
 * difference between "configured to be mock" and "wanted real but fell back".
 */
public enum ExecutionType {
    MOCK,
    REAL
}
