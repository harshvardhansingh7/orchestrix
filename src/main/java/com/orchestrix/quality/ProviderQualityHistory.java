package com.orchestrix.quality;

import com.orchestrix.domain.model.ProviderType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tracks an exponentially-decayed average quality score per provider.
 * The provider ranker reads this so a provider that consistently emits
 * low-quality output gets nudged down even when it's healthy and cheap.
 *
 * State is in-memory only — fine for the single-instance footprint.
 * Multi-replica deployments would back this with a shared store.
 */
@Component
public class ProviderQualityHistory {

    /** Decay factor — 0.1 means recent samples dominate, but tail still matters. */
    private static final double DECAY = 0.1;

    /** Default prior so a brand-new provider doesn't sit at 0/10. */
    private static final double PRIOR = 8.0;

    private final Map<ProviderType, double[]> averages = new EnumMap<>(ProviderType.class);

    public synchronized void record(ProviderType provider, double score) {
        double[] cur = averages.computeIfAbsent(provider, k -> new double[]{PRIOR});
        cur[0] = (1 - DECAY) * cur[0] + DECAY * score;
    }

    public synchronized double average(ProviderType provider) {
        double[] cur = averages.get(provider);
        return cur == null ? PRIOR : cur[0];
    }
}
