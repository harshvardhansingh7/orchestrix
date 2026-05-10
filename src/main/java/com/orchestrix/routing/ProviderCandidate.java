package com.orchestrix.routing;

import com.orchestrix.domain.model.ProviderType;
import lombok.Builder;
import lombok.Value;

/**
 * One row in the provider ranker's evaluation table. Captures every signal
 * that contributed to the ranker's verdict so a request's reasoning can
 * include "why X beat Y".
 *
 * Sub-scores are normalized to 0..10 for legibility (cost is inverted —
 * cheaper providers get higher costScore). The {@code finalWeightedScore}
 * is what the ranker sorts by.
 */
@Value
@Builder
public class ProviderCandidate {
    ProviderType provider;
    String model;
    int promptTokens;
    int outputTokens;
    double estimatedCostUsd;

    /** 0..10. Higher = cheaper. */
    double costScore;
    /** 0..10. Higher = faster. */
    double latencyScore;
    /** 0..10. Higher = more reliable. */
    double healthScore;
    /** 0..10. Higher = better historical quality. */
    double qualityScore;

    /** Weighted combination — what the ranker sorts by. */
    double finalWeightedScore;

    /** True iff the provider was excluded entirely from ranking. */
    boolean excluded;
    String exclusionReason;
}
