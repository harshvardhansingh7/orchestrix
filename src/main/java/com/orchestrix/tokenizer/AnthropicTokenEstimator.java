package com.orchestrix.tokenizer;

import com.orchestrix.domain.model.ProviderType;
import org.springframework.stereotype.Component;

/** Anthropic's tokenizer is slightly denser than OpenAI on English: ~3.5 chars/token. */
@Component
public class AnthropicTokenEstimator extends HeuristicTokenEstimator {
    public AnthropicTokenEstimator() {
        super(3.5);
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.ANTHROPIC;
    }
}
