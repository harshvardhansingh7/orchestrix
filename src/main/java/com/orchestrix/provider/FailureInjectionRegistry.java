package com.orchestrix.provider;

import com.orchestrix.domain.model.ProviderType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds in-memory failure injection rules per provider so the demo can
 * exercise resilience paths (timeouts, errors, slow responses) without
 * touching the upstream services.
 *
 * Modes:
 *   NONE      - normal pass-through (default).
 *   TIMEOUT   - delay every call past the request timeout.
 *   SLOW      - inject a fixed delay per call.
 *   ERROR     - return a transient error for the next N calls.
 *   ALWAYS_ERROR - return errors until cleared.
 */
@Slf4j
@Component
public class FailureInjectionRegistry {

    public enum Mode { NONE, TIMEOUT, SLOW, ERROR, ALWAYS_ERROR }

    @Getter
    public static class Rule {
        private final Mode mode;
        private final long delayMs;
        private final AtomicInteger remainingErrors;

        public Rule(Mode mode, long delayMs, int errorBurst) {
            this.mode = mode;
            this.delayMs = delayMs;
            this.remainingErrors = new AtomicInteger(errorBurst);
        }
    }

    private final Map<ProviderType, Rule> rules = new EnumMap<>(ProviderType.class);

    public void set(ProviderType provider, Mode mode, long delayMs, int errorBurst) {
        rules.put(provider, new Rule(mode, delayMs, errorBurst));
        log.warn("failure-injection set provider={} mode={} delayMs={} burst={}",
                provider, mode, delayMs, errorBurst);
    }

    public void clear(ProviderType provider) {
        rules.remove(provider);
        log.info("failure-injection cleared provider={}", provider);
    }

    public Rule rule(ProviderType provider) {
        return rules.getOrDefault(provider, new Rule(Mode.NONE, 0, 0));
    }

    public Map<ProviderType, Rule> snapshot() {
        return Map.copyOf(rules);
    }

    /**
     * Return a delay (ms) the caller should sleep before issuing the upstream
     * call, or -1 if the call should fail outright (callers throw a transient
     * provider exception).
     *
     * Important: callers should always invoke this once per attempt — it has
     * side effects (decrements error burst counters).
     */
    public long applyAndComputeDelay(ProviderType provider) {
        Rule rule = rule(provider);
        return switch (rule.mode) {
            case NONE -> 0L;
            case SLOW -> rule.delayMs;
            case TIMEOUT -> Math.max(rule.delayMs, 60_000L);
            case ALWAYS_ERROR -> -1L;
            case ERROR -> rule.remainingErrors.getAndDecrement() > 0 ? -1L : 0L;
        };
    }

    /** Used by tests / demo to introduce randomness in slow responses. */
    public long jitterMs(long base) {
        if (base <= 0) return 0;
        return base + ThreadLocalRandom.current().nextLong(50);
    }
}
