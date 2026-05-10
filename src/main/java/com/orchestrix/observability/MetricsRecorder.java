package com.orchestrix.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class MetricsRecorder {

    private final MeterRegistry registry;

    public void recordRequest(String tenantId, String provider, String model, String status,
                              long latencyMs, int tokens, double costUsd, boolean cacheHit, boolean fallbackUsed) {
        Tags base = Tags.of(
                "tenant", tenantId == null ? "unknown" : tenantId,
                "provider", provider == null ? "none" : provider,
                "model", model == null ? "none" : model,
                "status", status,
                "cache", String.valueOf(cacheHit),
                "fallback", String.valueOf(fallbackUsed));

        Counter.builder("orchestrix.requests")
                .tags(base)
                .register(registry)
                .increment();

        Timer.builder("orchestrix.llm.latency")
                .tags(base)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, latencyMs)));

        Counter.builder("orchestrix.tokens.total")
                .tags(base)
                .register(registry)
                .increment(tokens);

        Counter.builder("orchestrix.cost.usd")
                .tags(base)
                .register(registry)
                .increment(costUsd);
    }

    public void recordCacheHit(String tenantId) {
        Counter.builder("orchestrix.cache.hits")
                .tags("tenant", tenantId == null ? "unknown" : tenantId)
                .register(registry)
                .increment();
    }

    public void recordFallback(String tenantId, String fromProvider, String toProvider) {
        Counter.builder("orchestrix.fallback")
                .tags("tenant", tenantId == null ? "unknown" : tenantId,
                        "from", fromProvider, "to", toProvider)
                .register(registry)
                .increment();
    }

    public void recordRoutingScore(String tenantId, String tier, double score) {
        registry.timer("orchestrix.routing.score",
                "tenant", tenantId == null ? "unknown" : tenantId,
                "tier", tier).record((long) (score * 1000), TimeUnit.MILLISECONDS);
    }
}
