package com.orchestrix.routing;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.model.ModelTier;
import com.orchestrix.domain.model.PromptFeatures;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.provider.ProviderHealthTracker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Implements the scoring formula documented in DESIGN.md.
 *
 *   Complexity (C) = tokens*0.3 + is_code*2 + is_architecture*3 + is_debugging*2 + length_factor
 *   Cost        (K) = estimated_tokens * cost_per_token
 *   Reliability (R) = provider_health_score - latency_penalty - failure_rate_penalty
 *   FINAL = (C*W_C) + (R*W_R) - (K*W_K)
 *
 * The token contribution is normalized so it does not dominate the score for
 * very long prompts — without normalization a 4k-token prompt would push every
 * request to the high tier regardless of actual difficulty.
 */
@Component
@RequiredArgsConstructor
public class ScoringService {

    private final OrchestrixProperties props;
    private final ProviderHealthTracker healthTracker;
    private final CostCalculator costCalculator;

    public double complexityScore(PromptFeatures f) {
        // tokens contribution per the spec is `tokens * 0.3`. We normalize the
        // raw token count to a 0..10 scale (saturating at ~1500 tokens) so the
        // score stays in the published 0..15 routing band — otherwise large
        // prompts would always force the high tier regardless of intent.
        double normalizedTokens = Math.min(10.0, f.getEstimatedTokens() / 150.0);
        double tokenComponent = normalizedTokens * 0.3;
        double codeComponent = f.isHasCode() ? 2.0 : 0.0;
        double archComponent = f.isArchitectureQuery() ? 3.0 : 0.0;
        double debugComponent = f.isDebuggingQuery() ? 2.0 : 0.0;
        return tokenComponent + codeComponent + archComponent + debugComponent + f.getLengthFactor();
    }

    public double costScore(ProviderType provider, int estimatedTokens) {
        // Estimated USD cost assuming output ~= prompt size. We keep the cost
        // term in raw dollars: `K = tokens * price_per_token` per the spec.
        // For typical requests this is a small nudge (cents); for very large
        // prompts it becomes meaningful — exactly the desired behavior.
        return costCalculator.estimatedCostUsd(provider, estimatedTokens, estimatedTokens);
    }

    public double reliabilityScore(ProviderType provider) {
        double health = healthTracker.healthScore(provider);
        double avgLatency = healthTracker.avgLatencyMs(provider);
        double failureRate = healthTracker.failureRate(provider);

        double latencyPenalty = avgLatency > 1500 ? Math.min(0.5, (avgLatency - 1500) / 5000.0) : 0.0;
        double failurePenalty = Math.min(0.5, failureRate * 0.5);
        // Map health (0..1) to score (0..3) so it weighs comparably with complexity.
        return Math.max(0.0, (health * 3.0) - latencyPenalty - failurePenalty);
    }

    public double finalScore(double complexity, double reliability, double cost) {
        OrchestrixProperties.Routing r = props.getRouting();
        return (complexity * r.getWeightComplexity())
                + (reliability * r.getWeightReliability())
                - (cost * r.getWeightCost());
    }

    public ModelTier tierFromScore(double score) {
        OrchestrixProperties.Routing r = props.getRouting();
        if (score <= r.getScoreLowTierMax()) return ModelTier.LOW;
        if (score <= r.getScoreMidTierMax()) return ModelTier.MID;
        return ModelTier.HIGH;
    }
}
