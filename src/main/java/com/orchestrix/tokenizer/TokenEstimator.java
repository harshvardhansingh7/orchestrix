package com.orchestrix.tokenizer;

import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ProviderType;

import java.util.List;

/**
 * Provider-aware token estimator. Implementations are deliberately
 * lightweight approximations — exact tokenization (e.g., BPE) requires
 * provider-specific libraries we'd rather not pull in for the demo.
 *
 * The abstraction is the important part: when a real tokenizer (jtokkit,
 * etc.) is added later, we swap one implementation without touching the
 * routing engine, cost calculator, or budget service.
 *
 * Conventions:
 *  - {@link #estimateTokens(List)} is "what would the provider report as
 *    prompt_tokens for this message list?"
 *  - {@link #estimateOutputTokens(int)} is the heuristic we use at routing
 *    time before we know the actual completion size — typically "expect a
 *    response roughly equal to the prompt size, capped".
 */
public interface TokenEstimator {

    ProviderType providerType();

    int estimateTokens(List<ChatMessage> messages);

    default int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, estimateTokens(List.of(new ChatMessage("user", text))));
    }

    /**
     * Heuristic for expected completion size given prompt size — used at
     * routing time when only the prompt is known. Default: 1.0x prompt
     * tokens, clamped to a reasonable ceiling.
     */
    default int estimateOutputTokens(int promptTokens) {
        return Math.min(2048, Math.max(50, promptTokens));
    }
}
