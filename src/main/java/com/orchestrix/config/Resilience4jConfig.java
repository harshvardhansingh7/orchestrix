package com.orchestrix.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class Resilience4jConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(Throwable.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(cfg);
        // Pre-register one breaker per provider so health endpoint is honest from boot.
        registry.circuitBreaker("provider-openai");
        registry.circuitBreaker("provider-ollama");
        registry.circuitBreaker("provider-anthropic");
        return registry;
    }

    @Bean
    public RetryRegistry retryRegistry(OrchestrixProperties props) {
        RetryConfig cfg = RetryConfig.custom()
                .maxAttempts(Math.max(1, props.getResilience().getMaxRetries()))
                .waitDuration(Duration.ofMillis(props.getResilience().getRetryBaseBackoffMs()))
                .retryOnException(t -> {
                    if (t instanceof com.orchestrix.exception.ProviderException pe) {
                        return pe.isRetryable();
                    }
                    return true;
                })
                .build();
        return RetryRegistry.of(cfg);
    }
}
