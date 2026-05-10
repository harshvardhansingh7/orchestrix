package com.orchestrix.support;

import com.orchestrix.domain.model.ProviderInvocation;
import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.exception.ProviderException;
import com.orchestrix.provider.LLMProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Scenario-driven provider used by the API test report. Configurable for:
 *   - missing credentials (isConfigured=false)
 *   - timeouts (delay > 30s)
 *   - latency (configurable delay)
 *   - error bursts and always-error
 *   - mid-stream failure (emits N real chunks, then errors)
 *
 * Built on top of TestLLMProvider so existing tests still work.
 */
public class ConfigurableTestProvider implements LLMProvider {

    private final ProviderType type;
    private boolean enabled = true;
    private boolean configured = true;
    private long latencyMs = 0;
    private Supplier<Throwable> errorSupplier; // null = no error
    private int chunksBeforeFailure = -1;       // -1 = no mid-stream failure
    private final AtomicInteger callCount = new AtomicInteger();
    private String responseBody = "real-response";

    public ConfigurableTestProvider(ProviderType type) {
        this.type = type;
    }

    public ConfigurableTestProvider enabled(boolean v) { this.enabled = v; return this; }
    public ConfigurableTestProvider configured(boolean v) { this.configured = v; return this; }
    public ConfigurableTestProvider latency(long ms) { this.latencyMs = ms; return this; }
    public ConfigurableTestProvider response(String body) { this.responseBody = body; return this; }
    public ConfigurableTestProvider failsWith(Supplier<Throwable> err) { this.errorSupplier = err; return this; }
    public ConfigurableTestProvider transientError() {
        return failsWith(() -> new ProviderException(type, "transient", true));
    }
    public ConfigurableTestProvider permanentError() {
        return failsWith(() -> new ProviderException(type, "permanent", false));
    }
    public ConfigurableTestProvider midStreamFailureAfter(int chunks) {
        this.chunksBeforeFailure = chunks; return this;
    }

    public int callCount() { return callCount.get(); }

    @Override public ProviderType type() { return type; }
    @Override public boolean enabled() { return enabled; }
    @Override public boolean isConfigured() { return enabled && configured; }

    @Override
    public String resolveModel(String tier) {
        return Map.of("low", "low", "mid", "mid", "high", "high")
                .getOrDefault(tier == null ? "mid" : tier.toLowerCase(), "mid");
    }

    @Override
    public Mono<ProviderResponse> generate(ProviderInvocation invocation) {
        callCount.incrementAndGet();
        Mono<ProviderResponse> body = Mono.fromCallable(() -> {
            if (errorSupplier != null) {
                Throwable t = errorSupplier.get();
                if (t instanceof RuntimeException re) throw re;
                throw new RuntimeException(t);
            }
            return ProviderResponse.builder()
                    .content(responseBody)
                    .promptTokens(20)
                    .completionTokens(40)
                    .latencyMs(latencyMs)
                    .model(invocation.getModel())
                    .provider(type)
                    .build();
        });
        if (latencyMs > 0) {
            return Mono.delay(Duration.ofMillis(latencyMs)).then(body);
        }
        return body;
    }

    @Override
    public Flux<ProviderStreamChunk> stream(ProviderInvocation invocation) {
        callCount.incrementAndGet();
        if (errorSupplier != null && chunksBeforeFailure < 0) {
            return Flux.error(errorSupplier.get());
        }

        List<ProviderStreamChunk> chunks = List.of(
                ProviderStreamChunk.builder().delta("alpha ").done(false).build(),
                ProviderStreamChunk.builder().delta("beta ").done(false).build(),
                ProviderStreamChunk.builder().delta("gamma").done(false).build(),
                ProviderStreamChunk.builder()
                        .delta("")
                        .done(true)
                        .promptTokens(20)
                        .completionTokens(40)
                        .finishReason("stop")
                        .build());

        if (chunksBeforeFailure >= 0) {
            // Emit N chunks, then explode.
            return Flux.fromIterable(chunks)
                    .take(chunksBeforeFailure)
                    .concatWith(Flux.error(errorSupplier == null
                            ? new ProviderException(type, "mid-stream-failure", true)
                            : errorSupplier.get()));
        }
        if (latencyMs > 0) {
            return Flux.fromIterable(chunks)
                    .delayElements(Duration.ofMillis(Math.min(latencyMs, 50)));
        }
        return Flux.fromIterable(chunks);
    }
}
