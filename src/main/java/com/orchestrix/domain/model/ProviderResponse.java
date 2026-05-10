package com.orchestrix.domain.model;

import lombok.Builder;
import lombok.Value;

/**
 * Provider-agnostic non-streaming response shape.
 * Streaming flows return Flux<ProviderStreamChunk> instead.
 */
@Value
@Builder
public class ProviderResponse {
    String content;
    int promptTokens;
    int completionTokens;
    long latencyMs;
    String model;
    ProviderType provider;
}
