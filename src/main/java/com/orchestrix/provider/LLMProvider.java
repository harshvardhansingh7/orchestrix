package com.orchestrix.provider;

import com.orchestrix.domain.model.ProviderInvocation;
import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.domain.model.ProviderType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Adapter contract for an LLM provider. Implementations must be:
 *  - Stateless (per-instance config only).
 *  - Non-blocking (built on WebClient).
 *  - Failure-honest: surface upstream errors as ProviderException so the
 *    orchestrator can attribute health and decide retry/fallback.
 */
public interface LLMProvider {

    ProviderType type();

    boolean enabled();

    /**
     * True if this provider has the local prerequisites to make a real upstream
     * call right now (e.g. API key set, base URL configured). Independent of
     * runtime health — that is the circuit breaker's concern.
     *
     * Used by {@code ProviderExecutor} to short-circuit to a mock response in
     * REAL mode when credentials aren't present, instead of bubbling a 5xx.
     */
    default boolean isConfigured() {
        return enabled();
    }

    /** Resolve the concrete model name for the requested tier. */
    String resolveModel(String tier);

    Mono<ProviderResponse> generate(ProviderInvocation invocation);

    Flux<ProviderStreamChunk> stream(ProviderInvocation invocation);
}
