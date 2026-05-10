package com.orchestrix.routing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Human-readable explanation of why a particular routing decision was made.
 *
 * Surfaced in the API response (`routing` field of SimpleChatResponse), in
 * the structured event log, and persisted with the request log row so
 * historical "why did this prompt get gpt-4o-mini?" questions can be
 * answered without rerunning the request.
 *
 * Field shape matches the example documented in README §"Explainable
 * routing":
 *   {
 *     "complexityScore": 3.2,
 *     "reliabilityScore": 2.8,
 *     "costScore": 0.0004,
 *     "finalScore": 2.41,
 *     "selectedTier": "MID",
 *     "selectedProvider": "OPENAI",
 *     "reasoning": [...]
 *   }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingExplanation {

    private double complexityScore;
    private double reliabilityScore;
    private double costScore;
    private double finalScore;

    private String selectedTier;
    private String selectedProvider;
    private String selectedModel;

    private Integer estimatedPromptTokens;
    private Integer estimatedOutputTokens;
    private Double estimatedCostUsd;

    /**
     * Tag-style reasoning (e.g. "architecture_keywords_detected",
     * "tenant_max_tier=HIGH", "provider_health_good"). Easy to grep.
     */
    private List<String> reasoning;

    /** The full ranker table — useful for debugging "why did Y win over X?". */
    private List<RankedProviderView> candidates;

    /** Ordered fallback after the primary attempt. */
    private List<String> fallbackChain;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RankedProviderView {
        private String provider;
        private double finalScore;
        private double costScore;
        private double latencyScore;
        private double healthScore;
        private double qualityScore;
        private boolean excluded;
        private String exclusionReason;
    }
}
