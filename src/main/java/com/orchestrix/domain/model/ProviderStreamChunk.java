package com.orchestrix.domain.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ProviderStreamChunk {
    /** Token text. May be empty for control chunks. */
    String delta;

    /** True for the final chunk in a stream. */
    boolean done;

    /** Populated only on the final chunk. */
    Integer promptTokens;
    Integer completionTokens;

    /** Optional finish reason from the provider. */
    String finishReason;
}
