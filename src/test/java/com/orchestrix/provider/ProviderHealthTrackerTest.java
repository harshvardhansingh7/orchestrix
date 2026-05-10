package com.orchestrix.provider;

import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the upgraded health tracker: rolling window, p95, EWMA decay,
 * timeout/stream-interruption tracking, consecutive-failure penalty.
 */
class ProviderHealthTrackerTest {

    private ProviderHealthTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ProviderHealthTracker(
                mock(ProviderHealthRepository.class),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()));
        tracker.init();
    }

    @Test
    void newProviderHasFullHealth() {
        assertThat(tracker.healthScore(ProviderType.OPENAI)).isEqualTo(1.0);
    }

    @Test
    void successesKeepHealthHigh() {
        for (int i = 0; i < 10; i++) tracker.recordSuccess(ProviderType.OPENAI, 200);
        assertThat(tracker.healthScore(ProviderType.OPENAI)).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void failuresDropHealth() {
        for (int i = 0; i < 10; i++) tracker.recordFailure(ProviderType.OPENAI, 200);
        assertThat(tracker.healthScore(ProviderType.OPENAI)).isLessThan(0.7);
        assertThat(tracker.consecutiveFailures(ProviderType.OPENAI)).isEqualTo(10);
    }

    @Test
    void recoveryDecaysFailureRate() {
        for (int i = 0; i < 10; i++) tracker.recordFailure(ProviderType.OPENAI, 200);
        double whileFailing = tracker.healthScore(ProviderType.OPENAI);
        for (int i = 0; i < 50; i++) tracker.recordSuccess(ProviderType.OPENAI, 100);
        double afterRecovery = tracker.healthScore(ProviderType.OPENAI);
        assertThat(afterRecovery).isGreaterThan(whileFailing);
        assertThat(tracker.consecutiveFailures(ProviderType.OPENAI)).isEqualTo(0);
    }

    @Test
    void p95ReflectsRecentLatencies() {
        for (int i = 0; i < 90; i++) tracker.recordSuccess(ProviderType.OPENAI, 100);
        for (int i = 0; i < 10; i++) tracker.recordSuccess(ProviderType.OPENAI, 5000);
        double p95 = tracker.p95LatencyMs(ProviderType.OPENAI);
        assertThat(p95).isGreaterThanOrEqualTo(5000);
    }

    @Test
    void timeoutsAndStreamInterruptionsAreTrackedSeparately() {
        tracker.recordTimeout(ProviderType.OPENAI, 30_000);
        tracker.recordStreamInterruption(ProviderType.OPENAI);
        tracker.recordStreamInterruption(ProviderType.OPENAI);

        assertThat(tracker.timeouts(ProviderType.OPENAI)).isEqualTo(1);
        assertThat(tracker.streamInterruptions(ProviderType.OPENAI)).isEqualTo(2);
    }

    @Test
    void snapshotExposesAllSignals() {
        tracker.recordSuccess(ProviderType.OPENAI, 150);
        tracker.recordFailure(ProviderType.OPENAI, 200);
        ProviderHealthTracker.HealthSnapshot snap = tracker.snapshot(ProviderType.OPENAI);
        assertThat(snap.provider()).isEqualTo(ProviderType.OPENAI);
        assertThat(snap.healthScore()).isBetween(0.0, 1.0);
        assertThat(snap.recentFailureRate()).isBetween(0.0, 1.0);
        assertThat(snap.circuitState()).isEqualTo("CLOSED");
    }
}
