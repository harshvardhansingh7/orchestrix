package com.orchestrix.domain.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PromptFeatures {
    int estimatedTokens;
    int promptCharacters;
    boolean hasCode;
    boolean isArchitectureQuery;
    boolean isDebuggingQuery;
    /** Length factor in [0,1] that boosts complexity for longer prompts. */
    double lengthFactor;
}
