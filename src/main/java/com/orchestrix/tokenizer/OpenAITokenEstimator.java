package com.orchestrix.tokenizer;

import com.orchestrix.domain.model.ProviderType;
import org.springframework.stereotype.Component;

/** OpenAI cl100k_base BPE behaves close to ~4 chars / token on English text. */
@Component
public class OpenAITokenEstimator extends HeuristicTokenEstimator {
    public OpenAITokenEstimator() {
        super(4.0);
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.OPENAI;
    }
}
