package com.orchestrix.routing;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.ModelTier;
import com.orchestrix.domain.model.PromptFeatures;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.model.RoutingDecision;
import com.orchestrix.exception.NoProviderAvailableException;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.service.BudgetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which provider/model serves a given request.
 *
 * Pipeline:
 *   1. Extract prompt features (tokens, code/architecture/debug flags).
 *   2. Filter the eligible provider list (tenant allow-list ∩ healthy).
 *   3. Compute the score-derived tier (LOW/MID/HIGH).
 *   4. Apply tenant ceiling and budget overrides.
 *   5. Ask {@link ProviderRanker} to score every eligible provider for the
 *      chosen tier — the highest weighted score wins as primary; the rest
 *      become the fallback chain.
 *   6. Build a {@link RoutingExplanation} with tag-style reasoning so logs,
 *      API responses, and request_logs can answer "why this provider?".
 */
@Slf4j
@Component
public class RoutingEngine {

    private final FeatureExtractor featureExtractor;
    private final ScoringService scoring;
    private final ProviderRegistry providerRegistry;
    private final ProviderHealthTracker healthTracker;
    private final BudgetService budgetService;
    private final OrchestrixProperties props;
    private final ProviderRanker ranker;

    @Autowired
    public RoutingEngine(FeatureExtractor featureExtractor, ScoringService scoring,
                         ProviderRegistry providerRegistry, ProviderHealthTracker healthTracker,
                         BudgetService budgetService, OrchestrixProperties props,
                         ProviderRanker ranker) {
        this.featureExtractor = featureExtractor;
        this.scoring = scoring;
        this.providerRegistry = providerRegistry;
        this.healthTracker = healthTracker;
        this.budgetService = budgetService;
        this.props = props;
        this.ranker = ranker;
    }

    /**
     * Backward-compatible constructor for older tests that wire dependencies
     * by hand without a ranker. Falls back to the legacy "first-eligible"
     * provider selection when {@code ranker == null}.
     */
    public RoutingEngine(FeatureExtractor featureExtractor, ScoringService scoring,
                         ProviderRegistry providerRegistry, ProviderHealthTracker healthTracker,
                         BudgetService budgetService, OrchestrixProperties props) {
        this(featureExtractor, scoring, providerRegistry, healthTracker, budgetService, props, null);
    }

    public RoutingDecision route(Tenant tenant, ChatRequest request) {
        PromptFeatures features = featureExtractor.extract(request.getMessages());
        List<String> reasoning = new ArrayList<>();

        // ── Reasoning: prompt-driven complexity signals
        if (features.isHasCode()) reasoning.add("code_block_detected");
        if (features.isArchitectureQuery()) reasoning.add("architecture_keywords_detected");
        if (features.isDebuggingQuery()) reasoning.add("debugging_keywords_detected");
        reasoning.add("estimated_prompt_tokens=" + features.getEstimatedTokens());

        double complexity = scoring.complexityScore(features);

        List<ProviderType> eligible = eligibleProviders(tenant, reasoning);
        if (eligible.isEmpty()) {
            throw new NoProviderAvailableException("no providers are available for tenant " + tenant.getTenantId());
        }

        // Cost / reliability are computed against the first eligible — the
        // tier selection is a per-request scalar, not a per-provider one.
        ProviderType candidate = eligible.get(0);
        double cost = scoring.costScore(candidate, features.getEstimatedTokens());
        double reliability = scoring.reliabilityScore(candidate);
        double finalScore = scoring.finalScore(complexity, reliability, cost);

        ModelTier scoreTier = scoring.tierFromScore(finalScore);
        ModelTier tenantCeiling = parseTier(tenant.getMaxModelTier());
        reasoning.add("tenant_max_tier=" + tenantCeiling.name());
        reasoning.add("score_tier=" + scoreTier.name());

        boolean overrideApplied = false;
        StringBuilder overrideReason = new StringBuilder();

        ModelTier appliedTier = scoreTier;
        if (!scoreTier.atMost(tenantCeiling)) {
            appliedTier = tenantCeiling;
            overrideApplied = true;
            overrideReason.append("tenant-tier-ceiling=").append(tenantCeiling.name()).append(';');
            reasoning.add("override_tenant_tier_ceiling");
        }

        if (budgetService.isNearBudgetLimit(tenant)) {
            if (appliedTier != ModelTier.LOW) {
                appliedTier = ModelTier.LOW;
                overrideApplied = true;
                overrideReason.append("near-budget-limit;");
                reasoning.add("override_near_budget_limit");
            } else {
                reasoning.add("tenant_near_budget_limit");
            }
        } else {
            reasoning.add("estimated_cost_within_budget");
        }
        if (budgetService.isOverBudget(tenant)) {
            appliedTier = ModelTier.LOW;
            overrideApplied = true;
            overrideReason.append("over-daily-budget;");
            reasoning.add("override_over_daily_budget");
        }
        reasoning.add("applied_tier=" + appliedTier.name());

        // ── Provider ranking (or legacy fallback if ranker not wired)
        ProviderType primary;
        List<ProviderType> fallback;
        RoutingExplanation explanation;
        if (ranker != null) {
            ProviderRanker.Ranking ranking = ranker.rank(eligible, appliedTier, request.getMessages());
            primary = ranking.chosen() == null ? eligible.get(0) : ranking.chosen().getProvider();
            fallback = new ArrayList<>(ranking.orderedProviders());
            fallback.remove(primary);
            for (ProviderCandidate c : ranking.candidates()) {
                if (c.isExcluded()) continue;
                if (c.getProvider() == primary) {
                    reasoning.add("provider_chosen=" + primary.name()
                            + ",weighted_score=" + round2(c.getFinalWeightedScore()));
                }
            }
            explanation = buildExplanation(complexity, reliability, cost, finalScore, appliedTier,
                    primary, primaryModel(primary, appliedTier), features,
                    reasoning, ranking.candidates(), fallback);
        } else {
            primary = legacyPickProviderForTier(eligible, appliedTier);
            fallback = new ArrayList<>(eligible);
            fallback.remove(primary);
            reasoning.add("provider_chosen=" + primary.name() + ",legacy-mode");
            explanation = buildExplanation(complexity, reliability, cost, finalScore, appliedTier,
                    primary, primaryModel(primary, appliedTier), features,
                    reasoning, List.of(), fallback);
        }

        // Provider-health observations as reasoning tags.
        for (ProviderType pt : eligible) {
            double h = healthTracker.healthScore(pt);
            if (h >= 0.85) reasoning.add("provider_health_good=" + pt.name());
            else if (h >= 0.5) reasoning.add("provider_health_degraded=" + pt.name());
            else reasoning.add("provider_health_poor=" + pt.name());
        }

        String primaryModel = primaryModel(primary, appliedTier);

        return RoutingDecision.builder()
                .primaryProvider(primary)
                .primaryModel(primaryModel)
                .selectedTier(appliedTier)
                .fallbackProviders(fallback)
                .complexityScore(complexity)
                .costScore(cost)
                .reliabilityScore(reliability)
                .finalScore(finalScore)
                .estimatedTokens(features.getEstimatedTokens())
                .reason(buildReason(features, scoreTier, appliedTier))
                .overrideApplied(overrideApplied)
                .overrideReason(overrideReason.toString())
                .explanation(explanation)
                .build();
    }

    private List<ProviderType> eligibleProviders(Tenant tenant, List<String> reasoning) {
        List<ProviderType> ordered = new ArrayList<>();
        List<String> allowed = tenant.getAllowedProviders();
        for (ProviderType type : props.getFallbackChain()) {
            if (!allowed.contains(type.name())) {
                reasoning.add("provider_not_allowed=" + type.name());
                continue;
            }
            if (!providerRegistry.isEnabled(type)) {
                reasoning.add("provider_disabled=" + type.name());
                continue;
            }
            if (!healthTracker.isAvailable(type)) {
                reasoning.add("provider_circuit_open=" + type.name());
                log.info("excluding provider={} (circuit open) from routing for tenant={}", type, tenant.getTenantId());
                continue;
            }
            ordered.add(type);
        }
        return ordered;
    }

    private ProviderType legacyPickProviderForTier(List<ProviderType> eligible, ModelTier tier) {
        if (tier == ModelTier.LOW && eligible.contains(ProviderType.OLLAMA)) {
            return ProviderType.OLLAMA;
        }
        return eligible.get(0);
    }

    private String primaryModel(ProviderType primary, ModelTier tier) {
        LLMProvider primaryProvider = providerRegistry.get(primary)
                .orElseThrow(() -> new NoProviderAvailableException("primary provider not registered: " + primary));
        return primaryProvider.resolveModel(tier.name().toLowerCase());
    }

    private RoutingExplanation buildExplanation(double complexity, double reliability, double cost,
                                                double finalScore, ModelTier appliedTier,
                                                ProviderType primary, String primaryModel,
                                                PromptFeatures features, List<String> reasoning,
                                                List<ProviderCandidate> candidates,
                                                List<ProviderType> fallback) {
        ProviderCandidate primaryRow = candidates.stream()
                .filter(c -> c.getProvider() == primary && !c.isExcluded())
                .findFirst().orElse(null);
        return RoutingExplanation.builder()
                .complexityScore(round3(complexity))
                .reliabilityScore(round3(reliability))
                .costScore(round4(cost))
                .finalScore(round3(finalScore))
                .selectedTier(appliedTier.name())
                .selectedProvider(primary.name())
                .selectedModel(primaryModel)
                .estimatedPromptTokens(primaryRow == null ? features.getEstimatedTokens() : primaryRow.getPromptTokens())
                .estimatedOutputTokens(primaryRow == null ? null : primaryRow.getOutputTokens())
                .estimatedCostUsd(primaryRow == null ? null : round4(primaryRow.getEstimatedCostUsd()))
                .reasoning(List.copyOf(reasoning))
                .candidates(candidates.stream().map(this::view).toList())
                .fallbackChain(fallback.stream().map(Enum::name).toList())
                .build();
    }

    private RoutingExplanation.RankedProviderView view(ProviderCandidate c) {
        return RoutingExplanation.RankedProviderView.builder()
                .provider(c.getProvider().name())
                .finalScore(round3(c.getFinalWeightedScore()))
                .costScore(round3(c.getCostScore()))
                .latencyScore(round3(c.getLatencyScore()))
                .healthScore(round3(c.getHealthScore()))
                .qualityScore(round3(c.getQualityScore()))
                .excluded(c.isExcluded())
                .exclusionReason(c.getExclusionReason())
                .build();
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static double round4(double v) { return Math.round(v * 10_000.0) / 10_000.0; }

    private ModelTier parseTier(String tierName) {
        try {
            return ModelTier.valueOf(tierName.trim().toUpperCase());
        } catch (Exception e) {
            return ModelTier.HIGH;
        }
    }

    private String buildReason(PromptFeatures f, ModelTier scoreTier, ModelTier appliedTier) {
        StringBuilder sb = new StringBuilder();
        sb.append("tokens=").append(f.getEstimatedTokens()).append(';');
        if (f.isHasCode()) sb.append("code;");
        if (f.isArchitectureQuery()) sb.append("architecture;");
        if (f.isDebuggingQuery()) sb.append("debugging;");
        sb.append("scoreTier=").append(scoreTier.name()).append(';');
        sb.append("appliedTier=").append(appliedTier.name()).append(';');
        return sb.toString();
    }
}
