package com.orchestrix.routing;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ModelTier;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.quality.ProviderQualityHistory;
import com.orchestrix.tokenizer.TokenEstimator;
import com.orchestrix.tokenizer.TokenEstimatorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Compares eligible providers head-to-head and picks the best one for the
 * given tier. Each candidate is evaluated on four normalized sub-scores
 * (cost, latency, health, quality) and combined with the configurable
 * weights from {@link OrchestrixProperties.CandidateWeights}.
 *
 * The ranker is deliberately separate from {@link RoutingEngine}:
 *   - Tier selection (LOW/MID/HIGH) is rule-based and stable.
 *   - Within-tier provider selection is data-driven and weighted.
 *
 * That separation keeps the formula tests stable while opening room for
 * smarter selection between equivalent-tier providers.
 */
@Component
@RequiredArgsConstructor
public class ProviderRanker {

    private final ProviderRegistry providerRegistry;
    private final ProviderHealthTracker healthTracker;
    private final TokenEstimatorRegistry tokenRegistry;
    private final ProviderQualityHistory qualityHistory;
    private final CostCalculator costCalculator;
    private final OrchestrixProperties props;

    /** Result of a single ranking pass — the table plus the chosen winner. */
    public record Ranking(List<ProviderCandidate> candidates, ProviderCandidate chosen) {
        public List<ProviderType> orderedProviders() {
            return candidates.stream()
                    .filter(c -> !c.isExcluded())
                    .map(ProviderCandidate::getProvider)
                    .toList();
        }
    }

    public Ranking rank(List<ProviderType> eligible, ModelTier tier, List<ChatMessage> messages) {
        OrchestrixProperties.CandidateWeights w = props.getRouting().getCandidateWeights();

        List<ProviderCandidate> rows = new ArrayList<>(eligible.size());
        for (ProviderType type : eligible) {
            LLMProvider p = providerRegistry.get(type).orElse(null);
            if (p == null || !p.enabled()) {
                rows.add(ProviderCandidate.builder()
                        .provider(type)
                        .excluded(true)
                        .exclusionReason("disabled-or-missing")
                        .build());
                continue;
            }
            String model = p.resolveModel(tier.name().toLowerCase());

            TokenEstimator est = tokenRegistry.forProvider(type);
            int promptTokens = Math.max(1, est.estimateTokens(messages));
            int outputTokens = est.estimateOutputTokens(promptTokens);
            double cost = costCalculator.estimatedCostUsd(type, promptTokens, outputTokens);

            // Sub-scores — all on 0..10 where higher is better.
            double costScore = costToScore(cost);
            double latencyScore = latencyToScore(healthTracker.p95LatencyMs(type));
            double healthScore = healthTracker.healthScore(type) * 10.0;
            double qualityScore = qualityHistory.average(type);

            double finalScore =
                      w.getCost()    * costScore
                    + w.getLatency() * latencyScore
                    + w.getHealth()  * healthScore
                    + w.getQuality() * qualityScore;

            rows.add(ProviderCandidate.builder()
                    .provider(type)
                    .model(model)
                    .promptTokens(promptTokens)
                    .outputTokens(outputTokens)
                    .estimatedCostUsd(cost)
                    .costScore(costScore)
                    .latencyScore(latencyScore)
                    .healthScore(healthScore)
                    .qualityScore(qualityScore)
                    .finalWeightedScore(finalScore)
                    .excluded(false)
                    .build());
        }

        ProviderCandidate winner = rows.stream()
                .filter(c -> !c.isExcluded())
                .max(Comparator.comparingDouble(ProviderCandidate::getFinalWeightedScore))
                .orElse(null);
        return new Ranking(rows, winner);
    }

    /**
     * Map cost to a 0..10 score. Free is 10; ~$0.20 USD lands at 0.
     * Anything above 0.20 stays at 0 (never negative).
     */
    private double costToScore(double costUsd) {
        if (costUsd <= 0) return 10.0;
        double penalty = Math.min(10.0, costUsd / 0.020);
        return Math.max(0.0, 10.0 - penalty);
    }

    /**
     * Map p95 latency to a 0..10 score. <250ms is 10; ~5s is 0.
     */
    private double latencyToScore(double p95Ms) {
        if (p95Ms <= 250) return 10.0;
        double penalty = Math.min(10.0, (p95Ms - 250) / 475.0);
        return Math.max(0.0, 10.0 - penalty);
    }
}
