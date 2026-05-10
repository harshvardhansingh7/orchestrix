package com.orchestrix.quality;

import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderType;

/**
 * Evaluates response quality. Today the only implementation is a heuristic
 * one ({@link HeuristicQualityEvaluator}); the abstraction exists so a
 * proper "LLM-as-judge" evaluator can be plugged in without the rest of
 * the system caring.
 */
public interface QualityEvaluator {

    /**
     * Assess a non-streaming response. Caller supplies the prompt token count
     * so the evaluator can reason about length adequacy.
     */
    QualitySignals evaluate(ProviderType provider, ProviderResponse response, int promptTokens, boolean streamInterrupted);
}
