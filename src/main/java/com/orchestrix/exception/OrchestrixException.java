package com.orchestrix.exception;

/** Base type for application-level errors that map to specific HTTP responses. */
public abstract class OrchestrixException extends RuntimeException {
    protected OrchestrixException(String message) {
        super(message);
    }

    protected OrchestrixException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract int httpStatus();
}
