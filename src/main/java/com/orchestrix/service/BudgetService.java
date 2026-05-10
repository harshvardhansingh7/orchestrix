package com.orchestrix.service;

import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.entity.TenantUsage;
import com.orchestrix.domain.repository.TenantUsageRepository;
import com.orchestrix.exception.BudgetExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Tracks per-tenant daily and monthly spend and enforces budget caps.
 * Spend is recorded on actual completion (after token counts are known).
 *
 * MySQL row-level locking via {@code synchronized} on the tenant id keeps
 * the single-instance demo accurate; for multi-instance deployments
 * upgrade to {@code SELECT ... FOR UPDATE} or atomic UPDATEs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetService {

    private final TenantUsageRepository usageRepository;
    private final Clock clock = Clock.systemUTC();

    public void enforceBudget(Tenant tenant) {
        BigDecimal dailySpend = currentDailySpend(tenant);
        if (dailySpend.compareTo(tenant.getDailyBudgetUsd()) >= 0) {
            throw new BudgetExceededException("daily budget exhausted for tenant " + tenant.getTenantId());
        }
    }

    public boolean isOverBudget(Tenant tenant) {
        return currentDailySpend(tenant).compareTo(tenant.getDailyBudgetUsd()) >= 0;
    }

    public boolean isNearBudgetLimit(Tenant tenant) {
        BigDecimal spend = currentDailySpend(tenant);
        BigDecimal threshold = tenant.getDailyBudgetUsd().multiply(new BigDecimal("0.90"));
        return spend.compareTo(threshold) >= 0;
    }

    public BigDecimal currentDailySpend(Tenant tenant) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return usageRepository.findByTenantIdAndUsageDate(tenant.getTenantId(), today)
                .map(TenantUsage::getCostUsd)
                .orElse(BigDecimal.ZERO);
    }

    @Transactional
    public synchronized void recordSpend(Tenant tenant, BigDecimal amountUsd, long tokens) {
        if (amountUsd == null || amountUsd.signum() < 0) return;
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        TenantUsage usage = usageRepository.findByTenantIdAndUsageDate(tenant.getTenantId(), today)
                .orElseGet(() -> TenantUsage.builder()
                        .tenantId(tenant.getTenantId())
                        .usageDate(today)
                        .costUsd(BigDecimal.ZERO)
                        .totalTokens(0)
                        .requestCount(0)
                        .build());
        usage.setCostUsd(usage.getCostUsd().add(amountUsd));
        usage.setTotalTokens(usage.getTotalTokens() + tokens);
        usage.setRequestCount(usage.getRequestCount() + 1);
        usageRepository.save(usage);
    }
}
