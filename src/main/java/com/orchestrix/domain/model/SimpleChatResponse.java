package com.orchestrix.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orchestrix.routing.RoutingExplanation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Demo-friendly response shape returned by {@code /v1/chat/completions}.
 *
 * The full {@link ChatResponse} keeps every internal field for logs and
 * persistence; this DTO trims it down to the fields a client actually wants:
 * what tier was chosen, which provider answered, whether it was real or
 * mock, the text content, the cost, and the latency.
 *
 * Fallback-chain and reason fields are kept (nullable) so debug clients can
 * see why a mock was served, but day-to-day use focuses on the top six fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SimpleChatResponse {

    private String requestId;
    private String tier;
    private String provider;
    private String model;
    private String mode;             // MOCK | REAL  (the actual execution outcome)
    private String response;         // the assistant text
    private BigDecimal cost;         // USD
    private Long latencyMs;
    private boolean fallbackUsed;
    private String fallbackChain;
    private String reason;           // populated when mock-served
    private String status;           // completed | error | incomplete_stream
    private RoutingExplanation routing;
    private Double qualityScore;     // 0..10 heuristic quality assessment
    private String qualityNote;

    public static SimpleChatResponse from(ChatResponse r) {
        return SimpleChatResponse.builder()
                .requestId(r.getRequestId())
                .tier(r.getTier())
                .provider(r.getProvider())
                .model(r.getModel())
                .mode(r.getExecutionType())
                .response(r.getContent())
                .cost(r.getCostUsd() == null ? null : new BigDecimal(r.getCostUsd()))
                .latencyMs(r.getLatencyMs())
                .fallbackUsed(r.isFallbackUsed())
                .fallbackChain(r.getFallbackChain())
                .reason(r.getExecutionReason())
                .status(r.getStatus())
                .routing(r.getRouting())
                .qualityScore(r.getQualityScore())
                .qualityNote(r.getQualityNote())
                .build();
    }
}
