package com.orchestrix.exception;

public class BudgetExceededException extends OrchestrixException {
    public BudgetExceededException(String message) {
        super(message);
    }

    @Override
    public int httpStatus() {
        return 402;
    }
}
