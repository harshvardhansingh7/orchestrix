package com.orchestrix.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orchestrix.routing.RoutingExplanation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {

    private String requestId;
    private String content;

    /** Resolved provider that produced this response. */
    private String provider;
    private String model;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    /** USD cost as a string to preserve precision when serialized. */
    private String costUsd;
    private Long latencyMs;

    private boolean fallbackUsed;
    private String fallbackChain;

    private boolean cacheHit;

    /** True when the response was delivered as an SSE stream. */
    private boolean streamed;

    /** "completed" | "incomplete_stream" | "error" */
    private String status;

    private String errorMessage;

    /** Selected tier (LOW | MID | HIGH) — surfaced for clients/logs/UI. */
    private String tier;

    /** Active system mode at execution time (MOCK | REAL | HYBRID). */
    private String mode;

    /** Whether the response came from a real upstream call or a mock (MOCK | REAL). */
    private String executionType;

    /** Human-readable reason when {@code executionType=MOCK}; null otherwise. */
    private String executionReason;

    /** Full routing explanation (scores, reasoning tags, candidate table). */
    private RoutingExplanation routing;

    /** Quality score (0..10) — heuristic by default; null if unevaluated. */
    private Double qualityScore;

    /** Quality note ("clean;", "malformed;", etc.) for legibility. */
    private String qualityNote;
}
