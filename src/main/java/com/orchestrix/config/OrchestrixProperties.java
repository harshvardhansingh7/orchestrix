package com.orchestrix.config;

import com.orchestrix.domain.model.ProviderType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "orchestrix")
public class OrchestrixProperties {

    /** Execution mode: MOCK | REAL | HYBRID. See {@code com.orchestrix.mode.ExecutionMode}. */
    private String mode = "HYBRID";

    private Cache cache = new Cache();
    private Routing routing = new Routing();
    private Resilience resilience = new Resilience();
    private Map<String, Provider> providers = Map.of();
    private List<ProviderType> fallbackChain = List.of();
    private FailureInjection failureInjection = new FailureInjection();

    @Getter @Setter
    public static class Cache {
        private boolean enabled = true;
        private long ttlSeconds = 600;
        private boolean redisEnabled = false;
        private int inMemoryMaxEntries = 5000;
    }

    @Getter @Setter
    public static class Routing {
        private double weightComplexity = 0.5;
        private double weightReliability = 0.3;
        private double weightCost = 0.2;
        // Defaults are calibrated for the actual final-score range
        // (~0.5 .. ~5.5) produced by the documented formula. See DESIGN.md.
        private double scoreLowTierMax = 1.5;
        private double scoreMidTierMax = 3.5;

        /**
         * Per-candidate provider ranking weights. Used by ProviderRanker to
         * pick the best provider within the chosen tier. Each candidate is
         * scored on cost, latency, health, and quality; the weights below
         * tune which signal dominates when picking among eligibles.
         *
         * The cost weight is negative because cost penalizes the candidate.
         * Other weights are positive — higher health / quality / lower
         * latency = better candidate.
         */
        private CandidateWeights candidateWeights = new CandidateWeights();
    }

    @Getter @Setter
    public static class CandidateWeights {
        private double cost = 1.5;        // higher = stronger cost preference
        private double latency = 1.0;     // higher = stronger preference for fast providers
        private double health = 2.0;      // higher = stronger reliability preference
        private double quality = 1.0;     // higher = stronger quality preference
    }

    @Getter @Setter
    public static class Resilience {
        private long requestTimeoutMs = 30_000;
        private int maxRetries = 3;
        private long retryBaseBackoffMs = 200;
    }

    @Getter @Setter
    public static class Provider {
        private boolean enabled = true;
        private String baseUrl;
        private String apiKey;
        private double costPer1kInputTokens;
        private double costPer1kOutputTokens;
        private Map<String, String> models = Map.of();
    }

    @Getter @Setter
    public static class FailureInjection {
        private boolean enabled = true;
    }
}
