package com.orchestrix.tokenizer;

import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ProviderType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Per-provider {@link TokenEstimator} lookup. Defaults to the OpenAI
 * heuristic when a provider has no registered estimator — keeps any future
 * provider working without crashing the routing engine.
 */
@Component
public class TokenEstimatorRegistry {

    private final Map<ProviderType, TokenEstimator> byType = new EnumMap<>(ProviderType.class);
    private final TokenEstimator fallback;

    public TokenEstimatorRegistry(List<TokenEstimator> estimators) {
        for (TokenEstimator e : estimators) {
            byType.put(e.providerType(), e);
        }
        this.fallback = byType.getOrDefault(ProviderType.OPENAI, new OpenAITokenEstimator());
    }

    public TokenEstimator forProvider(ProviderType provider) {
        return byType.getOrDefault(provider, fallback);
    }

    /**
     * Convenience: a provider-agnostic token estimate used by the routing
     * engine for the complexity score. Uses the OpenAI estimator as a
     * neutral baseline so the score is reproducible regardless of which
     * provider ends up serving the request.
     */
    public int neutralEstimate(List<ChatMessage> messages) {
        return fallback.estimateTokens(messages);
    }
}
