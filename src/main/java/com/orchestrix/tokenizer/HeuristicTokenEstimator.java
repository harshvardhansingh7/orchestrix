package com.orchestrix.tokenizer;

import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ProviderType;

import java.util.List;

/**
 * Base heuristic tokenizer using the well-known {@code chars/4} approximation
 * with adjustments for whitespace and punctuation. Used by all provider
 * estimators with their own per-provider tuning.
 *
 * The estimator counts:
 *   - 1 token per ~4 characters of mixed text.
 *   - +1 token per chat message envelope (role wrapper).
 *   - Adjusted by a provider-specific factor.
 *
 * Provider-specific subclasses tweak the chars-per-token ratio because real
 * tokenizers differ measurably:
 *   OpenAI BPE (cl100k_base): ~4 chars / token on English.
 *   Anthropic claude-3 tokenizer: ~3.5 chars / token (slightly denser).
 *   Llama / Ollama: ~4.2 chars / token (sentencepiece, a bit looser).
 *
 * If/when a real tokenizer library is added (jtokkit, anthropic-tokenizer),
 * subclass this with the precise implementation — the rest of the system
 * already speaks via {@link TokenEstimator}.
 */
public abstract class HeuristicTokenEstimator implements TokenEstimator {

    private final double charsPerToken;

    protected HeuristicTokenEstimator(double charsPerToken) {
        this.charsPerToken = charsPerToken;
    }

    @Override
    public int estimateTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;
        for (ChatMessage m : messages) {
            String content = m.getContent() == null ? "" : m.getContent();
            total += (int) Math.ceil(content.length() / charsPerToken);
            // Each message carries a role marker that real tokenizers count.
            total += 4;
        }
        // Add a small constant for the chat-template framing.
        total += 3;
        return Math.max(1, total);
    }
}
