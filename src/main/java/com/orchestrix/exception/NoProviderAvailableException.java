package com.orchestrix.exception;

public class NoProviderAvailableException extends OrchestrixException {
    public NoProviderAvailableException(String message) {
        super(message);
    }

    @Override
    public int httpStatus() {
        return 503;
    }
}
