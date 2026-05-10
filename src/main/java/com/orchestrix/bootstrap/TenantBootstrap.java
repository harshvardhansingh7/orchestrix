package com.orchestrix.bootstrap;

import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.repository.TenantRepository;
import com.orchestrix.security.ApiKeyHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Seeds two demo tenants on first boot (dev/test profiles only) so users can
 * exercise the API without a manual SQL step. Idempotent — looks up by tenantId.
 *
 * Demo keys (do not use in production):
 *   tenant-acme    -> orx_acme_demo_key_123
 *   tenant-globex  -> orx_globex_demo_key_456
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile({"dev", "test"})
public class TenantBootstrap implements CommandLineRunner {

    private final TenantRepository tenantRepository;
    private final ApiKeyHasher hasher;

    @Override
    public void run(String... args) {
        seed("tenant-acme", "Acme Corp", "orx_acme_demo_key_123",
                new BigDecimal("50.0000"), new BigDecimal("1000.0000"),
                120, 10, "OPENAI,OLLAMA", "HIGH");

        seed("tenant-globex", "Globex Inc", "orx_globex_demo_key_456",
                new BigDecimal("20.0000"), new BigDecimal("500.0000"),
                60, 5, "OPENAI,ANTHROPIC,OLLAMA", "MID");
    }

    private void seed(String tenantId, String name, String rawKey,
                      BigDecimal daily, BigDecimal monthly,
                      int rpm, int rps, String providers, String maxTier) {
        if (tenantRepository.findByTenantId(tenantId).isPresent()) {
            return;
        }
        Tenant t = Tenant.builder()
                .tenantId(tenantId)
                .name(name)
                .apiKeyHash(hasher.hash(rawKey))
                .dailyBudgetUsd(daily)
                .monthlyBudgetUsd(monthly)
                .rateLimitRpm(rpm)
                .rateLimitRps(rps)
                .allowedProvidersCsv(providers)
                .maxModelTier(maxTier)
                .enabled(true)
                .build();
        tenantRepository.save(t);
        log.info("seeded demo tenant tenantId={} rawKey={} (dev only)", tenantId, rawKey);
    }
}
