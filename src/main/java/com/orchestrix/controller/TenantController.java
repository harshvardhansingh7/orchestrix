package com.orchestrix.controller;

import com.orchestrix.domain.entity.RequestLog;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.mode.ModeService;
import com.orchestrix.security.TenantContext;
import com.orchestrix.service.BudgetService;
import com.orchestrix.service.ChatCompletionService;
import com.orchestrix.service.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tenant")
public class TenantController {

    private final ChatCompletionService completionService;
    private final BudgetService budgetService;
    private final RateLimitService rateLimitService;
    private final ModeService modeService;

    /** GET /tenant/state — same body as /tenant/me, with current execution mode. */
    @GetMapping({"/state", "/me"})
    public ResponseEntity<Map<String, Object>> state() {
        Tenant tenant = TenantContext.require();
        BigDecimal spent = budgetService.currentDailySpend(tenant);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenant.getTenantId());
        body.put("name", tenant.getName());
        body.put("dailyBudgetUsd", tenant.getDailyBudgetUsd());
        body.put("dailySpentUsd", spent);
        body.put("dailyRemainingUsd", tenant.getDailyBudgetUsd().subtract(spent));
        body.put("rateLimitRpm", tenant.getRateLimitRpm());
        body.put("rateLimitRps", tenant.getRateLimitRps());
        body.put("allowedProviders", tenant.getAllowedProviders());
        body.put("maxModelTier", tenant.getMaxModelTier());
        body.put("buckets", rateLimitService.snapshot(tenant.getTenantId()));
        body.put("currentExecutionMode", modeService.current().name());
        return ResponseEntity.ok(body);
    }

    /** GET /tenant/requests — last 50 requests for the calling tenant. */
    @GetMapping("/requests")
    public ResponseEntity<List<RequestLog>> recent() {
        Tenant tenant = TenantContext.require();
        return ResponseEntity.ok(completionService.recentLogsForTenant(tenant.getTenantId()));
    }
}
