package com.orchestrix.provider;

import com.orchestrix.domain.entity.ProviderHealthEntity;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-provider health tracker. Tracks the signals the routing engine and
 * provider ranker need to make informed decisions:
 *
 *   - rolling success / failure counts (last N samples)
 *   - p95 latency over the rolling window
 *   - timeout, retry, and stream-interruption counts
 *   - consecutive-failure spikes
 *   - circuit-breaker state
 *   - exponentially-decayed health score (recent matters more)
 *
 * The window is in-memory and bounded; we periodically flush a summary to
 * MySQL. Older windows are not persisted — the rolling stats live or die
 * with the JVM, and `provider_health` is the durable summary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderHealthTracker {

    /** Sliding-window size for latency / outcome stats. Larger = smoother. */
    private static final int WINDOW = 200;

    /** Decay factor for the EWMA of failure rate. 0.05 → fast adaptation. */
    private static final double DECAY_ALPHA = 0.05;

    private final ProviderHealthRepository repository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    private final Map<ProviderType, Stats> stats = new EnumMap<>(ProviderType.class);

    @PostConstruct
    public void init() {
        for (ProviderType t : ProviderType.values()) {
            stats.put(t, new Stats());
        }
    }

    // ────────────────────────── Recording ──────────────────────────

    public synchronized void recordSuccess(ProviderType provider, long latencyMs) {
        Stats s = stat(provider);
        s.totalCalls.incrementAndGet();
        s.totalLatency.addAndGet(latencyMs);
        s.consecutiveFailures.set(0);
        s.lastSuccessAt = Instant.now();
        push(s.recentLatencies, latencyMs);
        push(s.recentOutcomes, 0); // success = 0
        s.failureRateEwma = (1 - DECAY_ALPHA) * s.failureRateEwma + DECAY_ALPHA * 0.0;
    }

    public synchronized void recordFailure(ProviderType provider, long latencyMs) {
        Stats s = stat(provider);
        s.totalCalls.incrementAndGet();
        s.totalFailures.incrementAndGet();
        s.totalLatency.addAndGet(latencyMs);
        s.consecutiveFailures.incrementAndGet();
        s.lastFailureAt = Instant.now();
        push(s.recentLatencies, latencyMs);
        push(s.recentOutcomes, 1); // failure = 1
        s.failureRateEwma = (1 - DECAY_ALPHA) * s.failureRateEwma + DECAY_ALPHA * 1.0;
    }

    public synchronized void recordTimeout(ProviderType provider, long latencyMs) {
        Stats s = stat(provider);
        s.totalTimeouts.incrementAndGet();
        recordFailure(provider, latencyMs); // a timeout is also a failure
    }

    public synchronized void recordRetry(ProviderType provider) {
        stat(provider).totalRetries.incrementAndGet();
    }

    public synchronized void recordStreamInterruption(ProviderType provider) {
        stat(provider).streamInterruptions.incrementAndGet();
    }

    // ────────────────────────── Reading stats ──────────────────────────

    public double healthScore(ProviderType provider) {
        Stats s = stat(provider);
        long total = s.totalCalls.get();
        if (total == 0) {
            // Brand-new provider gets a generous prior; circuit state still gates it.
            return cbFactor(provider);
        }

        double recentFailureRate = recentFailureRate(provider);
        double p95 = p95LatencyMs(provider);
        long consecutive = s.consecutiveFailures.get();
        double interruptionRate = total == 0 ? 0
                : Math.min(1.0, (double) s.streamInterruptions.get() / Math.max(1, total));

        double base = 1.0;
        double failurePenalty = Math.min(0.5, recentFailureRate * 0.7);
        double latencyPenalty = p95 > 2000 ? Math.min(0.3, (p95 - 2000) / 10_000.0) : 0.0;
        double consecutivePenalty = consecutive >= 3 ? Math.min(0.2, consecutive * 0.05) : 0.0;
        double interruptionPenalty = Math.min(0.15, interruptionRate * 0.5);

        double cbFactor = cbFactor(provider);
        double score = (base - failurePenalty - latencyPenalty - consecutivePenalty - interruptionPenalty) * cbFactor;
        return Math.max(0.0, Math.min(1.0, score));
    }

    public double failureRate(ProviderType provider) {
        Stats s = stat(provider);
        long total = s.totalCalls.get();
        return total == 0 ? 0.0 : (double) s.totalFailures.get() / total;
    }

    public double recentFailureRate(ProviderType provider) {
        synchronized (this) {
            Stats s = stat(provider);
            if (s.recentOutcomes.isEmpty()) return s.failureRateEwma;
            int sum = 0;
            for (int o : s.recentOutcomes) sum += o;
            double window = (double) sum / s.recentOutcomes.size();
            // Average the windowed and EWMA views — windowed handles bursty
            // failures, EWMA reflects long-tail drift.
            return (window + s.failureRateEwma) / 2.0;
        }
    }

    public double avgLatencyMs(ProviderType provider) {
        Stats s = stat(provider);
        long total = s.totalCalls.get();
        return total == 0 ? 0.0 : (double) s.totalLatency.get() / total;
    }

    public double p95LatencyMs(ProviderType provider) {
        synchronized (this) {
            Stats s = stat(provider);
            if (s.recentLatencies.isEmpty()) return 0.0;
            List<Long> sorted = new ArrayList<>(s.recentLatencies);
            Collections.sort(sorted);
            int idx = (int) Math.floor(0.95 * (sorted.size() - 1));
            return sorted.get(idx);
        }
    }

    public long consecutiveFailures(ProviderType provider) {
        return stat(provider).consecutiveFailures.get();
    }

    public long timeouts(ProviderType provider) {
        return stat(provider).totalTimeouts.get();
    }

    public long retries(ProviderType provider) {
        return stat(provider).totalRetries.get();
    }

    public long streamInterruptions(ProviderType provider) {
        return stat(provider).streamInterruptions.get();
    }

    public boolean isAvailable(ProviderType provider) {
        return !"OPEN".equals(circuitState(provider));
    }

    public String circuitState(ProviderType provider) {
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("provider-" + provider.name().toLowerCase());
            return cb.getState().name();
        } catch (Exception e) {
            return "CLOSED";
        }
    }

    /**
     * Snapshot of the health signals — used by the provider ranker, admin
     * endpoints, and explanation generation.
     */
    public HealthSnapshot snapshot(ProviderType provider) {
        return new HealthSnapshot(
                provider,
                healthScore(provider),
                recentFailureRate(provider),
                avgLatencyMs(provider),
                p95LatencyMs(provider),
                timeouts(provider),
                retries(provider),
                streamInterruptions(provider),
                consecutiveFailures(provider),
                circuitState(provider));
    }

    // ────────────────────────── Persistence flush ──────────────────────────

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    @Transactional
    public void flushToDatabase() {
        for (ProviderType provider : ProviderType.values()) {
            try {
                ProviderHealthEntity ent = repository.findByProvider(provider.name())
                        .orElseGet(() -> ProviderHealthEntity.builder()
                                .provider(provider.name())
                                .circuitState("CLOSED")
                                .healthScore(1.0)
                                .build());
                Stats s = stat(provider);
                ent.setHealthScore(healthScore(provider));
                ent.setFailureRate(failureRate(provider));
                ent.setAvgLatencyMs(avgLatencyMs(provider));
                ent.setCircuitState(circuitState(provider));
                ent.setConsecutiveFailures((int) s.consecutiveFailures.get());
                ent.setLastSuccessAt(s.lastSuccessAt);
                ent.setLastFailureAt(s.lastFailureAt);
                repository.save(ent);
            } catch (Exception e) {
                log.warn("provider-health flush failed provider={} err={}", provider, e.getMessage());
            }
        }
    }

    // ────────────────────────── helpers ──────────────────────────

    private Stats stat(ProviderType provider) {
        return stats.computeIfAbsent(provider, k -> new Stats());
    }

    private <T> void push(Deque<T> q, T value) {
        q.addLast(value);
        while (q.size() > WINDOW) q.pollFirst();
    }

    private double cbFactor(ProviderType provider) {
        return switch (circuitState(provider)) {
            case "OPEN" -> 0.0;
            case "HALF_OPEN" -> 0.5;
            default -> 1.0;
        };
    }

    private static class Stats {
        AtomicLong totalCalls = new AtomicLong();
        AtomicLong totalFailures = new AtomicLong();
        AtomicLong totalLatency = new AtomicLong();
        AtomicLong totalTimeouts = new AtomicLong();
        AtomicLong totalRetries = new AtomicLong();
        AtomicLong streamInterruptions = new AtomicLong();
        AtomicLong consecutiveFailures = new AtomicLong();
        volatile Instant lastSuccessAt;
        volatile Instant lastFailureAt;
        Deque<Long> recentLatencies = new ArrayDeque<>(WINDOW);
        Deque<Integer> recentOutcomes = new ArrayDeque<>(WINDOW); // 0=success, 1=failure
        volatile double failureRateEwma = 0.0;
    }

    /** Read-only view of all health signals for one provider. */
    public record HealthSnapshot(
            ProviderType provider,
            double healthScore,
            double recentFailureRate,
            double avgLatencyMs,
            double p95LatencyMs,
            long timeouts,
            long retries,
            long streamInterruptions,
            long consecutiveFailures,
            String circuitState) {}
}
