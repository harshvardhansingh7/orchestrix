package com.orchestrix.controller;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.entity.ProviderHealthEntity;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import com.orchestrix.mode.ExecutionMode;
import com.orchestrix.mode.ModeService;
import com.orchestrix.provider.FailureInjectionRegistry;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.provider.ProviderRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for operating Orchestrix locally and during demos.
 *
 * Authenticated by the same API key filter; in production, gate this with
 * a separate "admin" tenant role or an mTLS-protected admin path.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final FailureInjectionRegistry failureInjection;
    private final ProviderHealthTracker healthTracker;
    private final ProviderHealthRepository healthRepository;
    private final ProviderRegistry providerRegistry;
    private final ModeService modeService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final OrchestrixProperties props;

    // ───────────────────────────── Mode switching ─────────────────────────────

    /**
     * GET /admin/mode  →  current mode.
     */
    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> currentMode() {
        return ResponseEntity.ok(Map.of(
                "mode", modeService.current().name(),
                "validModes", List.of("MOCK", "REAL", "HYBRID")));
    }

    /**
     * POST /admin/mode/switch
     * body: { "mode": "MOCK" | "REAL" | "HYBRID" }
     *
     * Atomic flip — every subsequent request honors the new mode immediately.
     */
    @PostMapping("/mode/switch")
    public ResponseEntity<Map<String, Object>> switchMode(@RequestBody Map<String, Object> body) {
        String requested = body == null ? null : String.valueOf(body.get("mode"));
        ExecutionMode parsed = ExecutionMode.parse(requested, null);
        if (parsed == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid mode",
                    "received", requested,
                    "validModes", List.of("MOCK", "REAL", "HYBRID")));
        }
        ExecutionMode applied = modeService.setMode(parsed);
        return ResponseEntity.ok(Map.of("mode", applied.name(), "switched", true));
    }

    // ─────────────────────────── Provider status ──────────────────────────────

    /**
     * GET /admin/provider/status
     * Per-provider snapshot: enabled, configured (real call possible), live
     * health (failure rate, latency, circuit state), and what tier mock would
     * say if used.
     */
    @GetMapping("/provider/status")
    public ResponseEntity<List<Map<String, Object>>> providerStatus() {
        List<Map<String, Object>> rows = Arrays.stream(ProviderType.values())
                .map(this::providerStatusRow)
                .toList();
        return ResponseEntity.ok(rows);
    }

    // ─────────────────────────── Failure injection ────────────────────────────

    /**
     * POST /admin/providers/{provider}/fail
     * body: { "mode": "TIMEOUT|SLOW|ERROR|ALWAYS_ERROR", "delayMs": 5000, "burst": 5 }
     */
    @PostMapping("/providers/{provider}/fail")
    public ResponseEntity<Map<String, Object>> injectFailure(@PathVariable String provider,
                                                              @RequestBody Map<String, Object> body) {
        if (!props.getFailureInjection().isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("error", "failure injection disabled"));
        }
        ProviderType type = ProviderType.from(provider);
        FailureInjectionRegistry.Mode mode = FailureInjectionRegistry.Mode.valueOf(
                String.valueOf(body.getOrDefault("mode", "ERROR")).toUpperCase());
        long delay = parseLong(body.get("delayMs"), 0);
        int burst = (int) parseLong(body.get("burst"), 5);
        failureInjection.set(type, mode, delay, burst);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("provider", type.name());
        resp.put("mode", mode.name());
        resp.put("delayMs", delay);
        resp.put("burst", burst);
        return ResponseEntity.ok(resp);
    }

    /**
     * POST /admin/providers/{provider}/recover
     *
     * Clears the failure injection rule AND resets the circuit breaker.
     * Without resetting the breaker, a recently-tripped provider would stay
     * unusable for the full open-state cooldown even after admin recovery.
     */
    @PostMapping("/providers/{provider}/recover")
    public ResponseEntity<Map<String, Object>> recoverProvider(@PathVariable String provider) {
        ProviderType type = ProviderType.from(provider);
        failureInjection.clear(type);
        try {
            circuitBreakerRegistry
                    .circuitBreaker("provider-" + type.name().toLowerCase())
                    .reset();
        } catch (Exception ignore) {
            // Breaker may not exist yet; not fatal for recovery semantics.
        }
        return ResponseEntity.ok(Map.of(
                "provider", type.name(),
                "status", "recovered",
                "breakerState", healthTracker.circuitState(type)));
    }

    /** GET /admin/providers/health  - live snapshot of provider health. */
    @GetMapping("/providers/health")
    public ResponseEntity<List<Map<String, Object>>> health() {
        List<Map<String, Object>> out = Arrays.stream(ProviderType.values())
                .map(this::healthRow)
                .toList();
        return ResponseEntity.ok(out);
    }

    /** GET /admin/providers/health/persisted  - last-flushed health rows. */
    @GetMapping("/providers/health/persisted")
    public ResponseEntity<List<ProviderHealthEntity>> persistedHealth() {
        return ResponseEntity.ok(healthRepository.findAll());
    }

    // ─────────────────────────────── helpers ──────────────────────────────────

    private Map<String, Object> providerStatusRow(ProviderType type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", type.name());
        LLMProvider p = providerRegistry.get(type).orElse(null);
        boolean enabled = p != null && p.enabled();
        boolean configured = p != null && p.isConfigured();
        row.put("enabled", enabled);
        row.put("configured", configured);
        row.put("circuitState", healthTracker.circuitState(type));
        row.put("healthScore", healthTracker.healthScore(type));
        row.put("avgLatencyMs", healthTracker.avgLatencyMs(type));
        row.put("failureRate", healthTracker.failureRate(type));
        row.put("realCallPossible", enabled && configured && healthTracker.isAvailable(type));
        return row;
    }

    private Map<String, Object> healthRow(ProviderType type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", type.name());
        row.put("healthScore", healthTracker.healthScore(type));
        row.put("failureRate", healthTracker.failureRate(type));
        row.put("avgLatencyMs", healthTracker.avgLatencyMs(type));
        row.put("circuitState", healthTracker.circuitState(type));
        row.put("available", healthTracker.isAvailable(type));
        return row;
    }

    private long parseLong(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return def;
        }
    }
}
