package com.orchestrix.domain.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Internal provider call envelope. Carries everything the adapter needs
 * to issue a single attempt against one provider/model.
 */
@Value
@Builder
public class ProviderInvocation {
    String requestId;
    String tenantId;
    ProviderType provider;
    String model;
    List<ChatMessage> messages;
    Integer maxTokens;
    Double temperature;
}
