package com.orchestrix.exception;

import com.orchestrix.domain.model.ProviderType;
import lombok.Getter;

/**
 * Thrown by provider adapters when an upstream call fails in a way that should
 * trigger retry or fallback logic. Carries which provider was the source so the
 * orchestrator can update health metrics and exclude it from the fallback chain.
 */
@Getter
public class ProviderException extends OrchestrixException {

    private final ProviderType provider;
    private final boolean retryable;

    public ProviderException(ProviderType provider, String message, boolean retryable) {
        super(message);
        this.provider = provider;
        this.retryable = retryable;
    }

    public ProviderException(ProviderType provider, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.retryable = retryable;
    }

    @Override
    public int httpStatus() {
        return 502;
    }
}
