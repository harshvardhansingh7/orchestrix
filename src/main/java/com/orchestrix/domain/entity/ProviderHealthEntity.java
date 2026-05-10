package com.orchestrix.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "provider_health")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderHealthEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String provider;

    @Column(name = "health_score", nullable = false)
    private double healthScore;

    @Column(name = "failure_rate", nullable = false)
    private double failureRate;

    @Column(name = "avg_latency_ms", nullable = false)
    private double avgLatencyMs;

    @Column(name = "circuit_state", nullable = false, length = 24)
    private String circuitState;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = Instant.now();
    }
}
