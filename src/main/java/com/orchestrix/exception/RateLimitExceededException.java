package com.orchestrix.exception;

public class RateLimitExceededException extends OrchestrixException {
    public RateLimitExceededException(String message) {
        super(message);
    }

    @Override
    public int httpStatus() {
        return 429;
    }
}
