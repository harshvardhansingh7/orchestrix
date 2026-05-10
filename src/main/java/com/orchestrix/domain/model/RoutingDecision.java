package com.orchestrix.domain.model;

import com.orchestrix.routing.RoutingExplanation;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * The outcome of the routing engine for a given request.
 * Includes the score breakdown so observability and tests can assert on it.
 */
@Value
@Builder
public class RoutingDecision {

    /** Provider chosen for the first attempt. */
    ProviderType primaryProvider;

    /** Specific model chosen on the primary provider. */
    String primaryModel;

    /** Tier the engine selected before applying tenant ceilings. */
    ModelTier selectedTier;

    /** Ordered fallback chain after the primary attempt. */
    List<ProviderType> fallbackProviders;

    double complexityScore;
    double costScore;
    double reliabilityScore;
    double finalScore;

    /** Estimated tokens used to compute the cost score. */
    int estimatedTokens;

    /** Human-readable reason describing key features that drove the decision. */
    String reason;

    /** True if the decision was nudged by budget / safety overrides. */
    boolean overrideApplied;
    String overrideReason;

    /** Full explanation surfaced in API responses, logs, and request_logs. */
    RoutingExplanation explanation;
}
