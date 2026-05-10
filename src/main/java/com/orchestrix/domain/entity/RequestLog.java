package com.orchestrix.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "request_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "routing_score")
    private Double routingScore;

    @Column(name = "complexity_score")
    private Double complexityScore;

    @Column(name = "cost_score")
    private Double costScore;

    @Column(name = "reliability_score")
    private Double reliabilityScore;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "fallback_chain", length = 255)
    private String fallbackChain;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(nullable = false)
    private boolean streamed;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(length = 16)
    private String mode;

    @Column(name = "execution_type", length = 16)
    private String executionType;

    @Column(name = "execution_reason", length = 255)
    private String executionReason;

    /** Comma-separated reasoning tags from the routing explanation. */
    @Column(name = "routing_reasoning", length = 2048)
    private String routingReasoning;

    /** Full JSON of the RoutingExplanation for auditing. */
    @Column(name = "routing_explanation_json", columnDefinition = "TEXT")
    private String routingExplanationJson;

    @Column(name = "quality_score")
    private Double qualityScore;

    @Column(name = "quality_note", length = 255)
    private String qualityNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
