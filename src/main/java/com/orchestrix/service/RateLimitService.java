package com.orchestrix.service;

import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.exception.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket rate limiter with two windows (per-second + per-minute) per tenant.
 *
 * Uses an in-process map for the demo. For multi-instance deployments, swap the
 * bucket map for a Redis Lua-based bucket so limits are global.
 *
 * Tenant isolation: each tenant has its own bucket. A noisy neighbor cannot
 * exhaust another tenant's budget — they share no state.
 */
@Slf4j
@Service
public class RateLimitService {

    private final Map<String, Buckets> tenantBuckets = new ConcurrentHashMap<>();

    public void enforce(Tenant tenant) {
        Buckets b = tenantBuckets.computeIfAbsent(tenant.getTenantId(),
                k -> new Buckets(tenant.getRateLimitRps(), tenant.getRateLimitRpm()));
        b.tryConsume(tenant);
    }

    public BucketSnapshot snapshot(String tenantId) {
        Buckets b = tenantBuckets.get(tenantId);
        if (b == null) return new BucketSnapshot(0, 0);
        return new BucketSnapshot(b.secondTokens.availableNow(), b.minuteTokens.availableNow());
    }

    public record BucketSnapshot(double rpsAvailable, double rpmAvailable) {}

    private static class Buckets {
        final TokenBucket secondTokens;
        final TokenBucket minuteTokens;

        Buckets(int rps, int rpm) {
            this.secondTokens = new TokenBucket(Math.max(1, rps), Math.max(1, rps));
            this.minuteTokens = new TokenBucket(Math.max(1, rpm), Math.max(1, rpm) / 60.0);
        }

        synchronized void tryConsume(Tenant tenant) {
            if (!secondTokens.tryConsume(1)) {
                throw new RateLimitExceededException("rate limit exceeded (per-second) for tenant " + tenant.getTenantId());
            }
            if (!minuteTokens.tryConsume(1)) {
                throw new RateLimitExceededException("rate limit exceeded (per-minute) for tenant " + tenant.getTenantId());
            }
        }
    }

    private static class TokenBucket {
        private final double capacity;
        private final double refillTokensPerSecond;
        private double tokens;
        private long lastRefillMs;

        TokenBucket(double capacity, double refillTokensPerSecond) {
            this.capacity = capacity;
            this.refillTokensPerSecond = refillTokensPerSecond;
            this.tokens = capacity;
            this.lastRefillMs = System.currentTimeMillis();
        }

        synchronized boolean tryConsume(double n) {
            refill();
            if (tokens >= n) {
                tokens -= n;
                return true;
            }
            return false;
        }

        synchronized double availableNow() {
            refill();
            return tokens;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            double elapsedSeconds = (now - lastRefillMs) / 1000.0;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillTokensPerSecond);
            lastRefillMs = now;
        }
    }
}
