package com.orchestrix.exception;

public class AuthenticationException extends OrchestrixException {
    public AuthenticationException(String message) {
        super(message);
    }

    @Override
    public int httpStatus() {
        return 401;
    }
}
