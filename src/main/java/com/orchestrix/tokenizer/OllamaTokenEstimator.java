package com.orchestrix.tokenizer;

import com.orchestrix.domain.model.ProviderType;
import org.springframework.stereotype.Component;

/** Llama family (sentencepiece) is slightly looser than OpenAI: ~4.2 chars/token. */
@Component
public class OllamaTokenEstimator extends HeuristicTokenEstimator {
    public OllamaTokenEstimator() {
        super(4.2);
    }

    @Override
    public ProviderType providerType() {
        return ProviderType.OLLAMA;
    }
}
