package com.orchestrix.mode;

import com.orchestrix.domain.model.ModelTier;
import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.domain.model.ProviderType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Produces deterministic, tier-aware mock responses so the system is fully
 * usable without real API credentials.
 *
 * The format follows the published demo contract — e.g.
 * {@code "MOCK: OpenAI response for MID tier"} — so tests, logs, and demos
 * can assert on the exact string.
 */
@Component
public class MockResponseFactory {

    /** Token counts that mirror "small response" so cost/budget math still flows. */
    private static final int MOCK_PROMPT_TOKENS = 12;
    private static final int MOCK_COMPLETION_TOKENS = 24;

    public ProviderResponse build(ProviderType provider, ModelTier tier, String model) {
        return ProviderResponse.builder()
                .content(text(provider, tier))
                .promptTokens(MOCK_PROMPT_TOKENS)
                .completionTokens(MOCK_COMPLETION_TOKENS)
                .latencyMs(5)
                .model(model)
                .provider(provider)
                .build();
    }

    public Flux<ProviderStreamChunk> stream(ProviderType provider, ModelTier tier) {
        String body = text(provider, tier);
        return Flux.fromIterable(List.of(
                ProviderStreamChunk.builder()
                        .delta(body)
                        .done(false)
                        .build(),
                ProviderStreamChunk.builder()
                        .delta("")
                        .done(true)
                        .promptTokens(MOCK_PROMPT_TOKENS)
                        .completionTokens(MOCK_COMPLETION_TOKENS)
                        .finishReason("stop")
                        .build()));
    }

    public String text(ProviderType provider, ModelTier tier) {
        String providerLabel = switch (provider) {
            case OPENAI -> "OpenAI";
            case OLLAMA -> "Ollama";
            case ANTHROPIC -> "Anthropic";
        };
        return "MOCK: " + providerLabel + " response for " + tier.name() + " tier";
    }
}
