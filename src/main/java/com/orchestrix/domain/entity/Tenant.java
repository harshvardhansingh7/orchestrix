package com.orchestrix.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, unique = true, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "api_key_hash", nullable = false, unique = true, length = 128)
    private String apiKeyHash;

    @Column(name = "daily_budget_usd", nullable = false, precision = 12, scale = 4)
    private BigDecimal dailyBudgetUsd;

    @Column(name = "monthly_budget_usd", nullable = false, precision = 14, scale = 4)
    private BigDecimal monthlyBudgetUsd;

    @Column(name = "rate_limit_rpm", nullable = false)
    private int rateLimitRpm;

    @Column(name = "rate_limit_rps", nullable = false)
    private int rateLimitRps;

    @Column(name = "allowed_providers", nullable = false, length = 255)
    private String allowedProvidersCsv;

    @Column(name = "max_model_tier", nullable = false, length = 16)
    private String maxModelTier;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public List<String> getAllowedProviders() {
        if (allowedProvidersCsv == null || allowedProvidersCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedProvidersCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
