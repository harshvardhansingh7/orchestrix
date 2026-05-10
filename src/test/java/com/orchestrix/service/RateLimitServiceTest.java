package com.orchestrix.service;

import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceTest {

    @Test
    void rejectsAfterCapacityForOneTenant() {
        RateLimitService svc = new RateLimitService();
        Tenant t = tenant("alpha", 2, 60);

        svc.enforce(t);
        svc.enforce(t);
        assertThatThrownBy(() -> svc.enforce(t))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void noisyTenantDoesNotAffectOtherTenants() {
        RateLimitService svc = new RateLimitService();
        Tenant noisy = tenant("noisy", 1, 5);
        Tenant quiet = tenant("quiet", 1, 5);

        svc.enforce(noisy);
        assertThatThrownBy(() -> svc.enforce(noisy))
                .isInstanceOf(RateLimitExceededException.class);

        // The other tenant must still be able to consume its own bucket.
        svc.enforce(quiet);
        assertThat(svc.snapshot("quiet").rpsAvailable()).isLessThan(1.0);
    }

    private static Tenant tenant(String id, int rps, int rpm) {
        return Tenant.builder()
                .tenantId(id)
                .name(id)
                .apiKeyHash("hash-" + id)
                .dailyBudgetUsd(new BigDecimal("100.0000"))
                .monthlyBudgetUsd(new BigDecimal("3000.0000"))
                .rateLimitRps(rps)
                .rateLimitRpm(rpm)
                .allowedProvidersCsv("OPENAI,OLLAMA")
                .maxModelTier("HIGH")
                .enabled(true)
                .build();
    }
}
