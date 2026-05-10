package com.orchestrix.resilience;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.model.ProviderInvocation;
import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.exception.ProviderException;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderHealthTracker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Wraps each provider call with a per-provider circuit breaker, retry policy,
 * and timeout. Updates the health tracker on success/failure so the routing
 * engine has up-to-date reliability inputs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResilientProviderInvoker {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final ProviderHealthTracker healthTracker;
    private final OrchestrixProperties props;

    public Mono<ProviderResponse> invoke(LLMProvider provider, ProviderInvocation inv) {
        ProviderType type = provider.type();
        CircuitBreaker breaker = breaker(type);
        Retry retry = retry(type);

        long start = System.currentTimeMillis();
        return Mono.defer(() -> provider.generate(inv))
                .timeout(Duration.ofMillis(props.getResilience().getRequestTimeoutMs()))
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .transformDeferred(RetryOperator.of(retry))
                .doOnSuccess(r -> healthTracker.recordSuccess(type, System.currentTimeMillis() - start))
                .doOnError(t -> {
                    long elapsed = System.currentTimeMillis() - start;
                    if (t instanceof java.util.concurrent.TimeoutException
                            || (t.getMessage() != null && t.getMessage().contains("Timeout"))) {
                        healthTracker.recordTimeout(type, elapsed);
                    } else {
                        healthTracker.recordFailure(type, elapsed);
                    }
                    log.warn("provider-call-failed provider={} requestId={} err={}",
                            type, inv.getRequestId(), t.getMessage());
                })
                .onErrorMap(t -> t instanceof ProviderException ? t : new ProviderException(type, t.getMessage(), true, t));
    }

    public Flux<ProviderStreamChunk> invokeStream(LLMProvider provider, ProviderInvocation inv) {
        ProviderType type = provider.type();
        CircuitBreaker breaker = breaker(type);
        // Streaming retries are tricky (would replay tokens); we apply CB only.
        long start = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicInteger emittedChunks = new java.util.concurrent.atomic.AtomicInteger();
        return Flux.defer(() -> provider.stream(inv))
                .timeout(Duration.ofMillis(props.getResilience().getRequestTimeoutMs()))
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .doOnNext(c -> emittedChunks.incrementAndGet())
                .doOnComplete(() -> healthTracker.recordSuccess(type, System.currentTimeMillis() - start))
                .doOnError(t -> {
                    long elapsed = System.currentTimeMillis() - start;
                    // A failure after some chunks already streamed is a stream
                    // interruption — different signal from a complete failure.
                    if (emittedChunks.get() > 0) {
                        healthTracker.recordStreamInterruption(type);
                    }
                    if (t instanceof java.util.concurrent.TimeoutException) {
                        healthTracker.recordTimeout(type, elapsed);
                    } else {
                        healthTracker.recordFailure(type, elapsed);
                    }
                    log.warn("provider-stream-failed provider={} requestId={} emitted={} err={}",
                            type, inv.getRequestId(), emittedChunks.get(), t.getMessage());
                })
                .onErrorMap(t -> t instanceof ProviderException ? t : new ProviderException(type, t.getMessage(), true, t));
    }

    public CircuitBreaker breaker(ProviderType type) {
        return circuitBreakerRegistry.circuitBreaker("provider-" + type.name().toLowerCase());
    }

    private Retry retry(ProviderType type) {
        return retryRegistry.retry("provider-" + type.name().toLowerCase(),
                () -> RetryConfig.custom()
                        .maxAttempts(Math.max(1, props.getResilience().getMaxRetries()))
                        .intervalFunction(io.github.resilience4j.core.IntervalFunction
                                .ofExponentialBackoff(props.getResilience().getRetryBaseBackoffMs(), 2.0))
                        .retryOnException(t -> t instanceof ProviderException pe && pe.isRetryable())
                        .build());
    }
}
