package com.orchestrix.support;

import com.orchestrix.domain.model.ProviderInvocation;
import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.exception.ProviderException;
import com.orchestrix.provider.LLMProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic in-memory provider used by tests so we never make real
 * network calls. Configure failure/success per-attempt.
 */
public class TestLLMProvider implements LLMProvider {

    private final ProviderType type;
    private final boolean enabled;
    private final AtomicInteger remainingFailures;
    private final String response;
    private final Map<String, String> models;

    public TestLLMProvider(ProviderType type, boolean enabled, int failuresFirst, String response) {
        this(type, enabled, failuresFirst, response,
                Map.of("low", "test-low", "mid", "test-mid", "high", "test-high"));
    }

    public TestLLMProvider(ProviderType type, boolean enabled, int failuresFirst, String response,
                           Map<String, String> models) {
        this.type = type;
        this.enabled = enabled;
        this.remainingFailures = new AtomicInteger(failuresFirst);
        this.response = response;
        this.models = models;
    }

    public int callCount() {
        return calls.get();
    }

    private final AtomicInteger calls = new AtomicInteger(0);

    @Override
    public ProviderType type() {
        return type;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String resolveModel(String tier) {
        return models.getOrDefault(tier == null ? "mid" : tier.toLowerCase(), "test-mid");
    }

    @Override
    public Mono<ProviderResponse> generate(ProviderInvocation invocation) {
        calls.incrementAndGet();
        if (remainingFailures.getAndDecrement() > 0) {
            return Mono.error(new ProviderException(type, "test-injected-failure", true));
        }
        return Mono.just(ProviderResponse.builder()
                .content(response)
                .promptTokens(20)
                .completionTokens(40)
                .latencyMs(5)
                .model(invocation.getModel())
                .provider(type)
                .build());
    }

    @Override
    public Flux<ProviderStreamChunk> stream(ProviderInvocation invocation) {
        calls.incrementAndGet();
        if (remainingFailures.getAndDecrement() > 0) {
            return Flux.error(new ProviderException(type, "test-injected-failure", true));
        }
        return Flux.just(
                ProviderStreamChunk.builder().delta(response).done(false).build(),
                ProviderStreamChunk.builder()
                        .delta("")
                        .done(true)
                        .promptTokens(20)
                        .completionTokens(40)
                        .finishReason("stop")
                        .build());
    }
}
