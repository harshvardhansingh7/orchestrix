package com.orchestrix.observability;

import com.orchestrix.quality.QualitySignals;
import com.orchestrix.routing.RoutingExplanation;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-line structured event logger. Each call produces one record per the
 * shape documented in README.md / DESIGN.md so log aggregation can index on
 * tenant, provider, model, status, etc.
 */
@Slf4j
@Component
public class StructuredLogger {

    public void requestCompleted(String requestId, String tenantId, String provider, String model,
                                 long latencyMs, int promptTokens, int completionTokens, int totalTokens,
                                 double costUsd, double routingScore, boolean fallbackUsed,
                                 String fallbackChain, boolean cacheHit, String status) {
        Map<String, Object> kv = new LinkedHashMap<>();
        kv.put("event", "llm.request.completed");
        kv.put("requestId", requestId);
        kv.put("tenantId", tenantId);
        kv.put("provider", provider);
        kv.put("model", model);
        kv.put("latencyMs", latencyMs);
        kv.put("promptTokens", promptTokens);
        kv.put("completionTokens", completionTokens);
        kv.put("totalTokens", totalTokens);
        kv.put("costUsd", costUsd);
        kv.put("routingScore", routingScore);
        kv.put("fallbackUsed", fallbackUsed);
        kv.put("fallbackChain", fallbackChain);
        kv.put("cacheHit", cacheHit);
        kv.put("status", status);
        log.info("llm-request {}", StructuredArguments.entries(kv));
    }

    public void routingDecisionLogged(String requestId, String tenantId, String tier,
                                       double complexity, double cost, double reliability, double finalScore,
                                       boolean overrideApplied, String overrideReason) {
        Map<String, Object> kv = new LinkedHashMap<>();
        kv.put("event", "llm.routing.decision");
        kv.put("requestId", requestId);
        kv.put("tenantId", tenantId);
        kv.put("tier", tier);
        kv.put("complexity", complexity);
        kv.put("cost", cost);
        kv.put("reliability", reliability);
        kv.put("finalScore", finalScore);
        kv.put("overrideApplied", overrideApplied);
        kv.put("overrideReason", overrideReason);
        log.info("routing-decision {}", StructuredArguments.entries(kv));
    }

    public void fallbackUsed(String requestId, String tenantId, String fromProvider, String toProvider,
                             String reason) {
        Map<String, Object> kv = new LinkedHashMap<>();
        kv.put("event", "llm.fallback.used");
        kv.put("requestId", requestId);
        kv.put("tenantId", tenantId);
        kv.put("fromProvider", fromProvider);
        kv.put("toProvider", toProvider);
        kv.put("reason", reason);
        log.warn("fallback-used {}", StructuredArguments.entries(kv));
    }

    /**
     * Demo-friendly readable line printed alongside the JSON event.
     * Format follows the published demo contract:
     * {@code [ROUTING] requestId=... tier=... score=... provider=...}.
     */
    public void routingHumanLine(String requestId, String tier, double finalScore, String provider) {
        log.info("[ROUTING] requestId={} tier={} score={} provider={}",
                requestId, tier, String.format("%.3f", finalScore), provider);
    }

    /**
     * Extended routing line that quotes the most relevant reasoning tags.
     * Keeps the line readable by capping at 4 tags — full list is in the JSON event.
     */
    public void routingExplainedLine(String requestId, RoutingExplanation expl) {
        if (expl == null) return;
        List<String> reasoning = expl.getReasoning();
        String summary = (reasoning == null || reasoning.isEmpty())
                ? "(no reasoning tags)"
                : reasoning.stream().limit(6).reduce((a, b) -> a + ", " + b).orElse("");
        log.info("[ROUTING] requestId={} chose {}:{} (final={}, complexity={}, reliability={}, cost={}) — {}",
                requestId, expl.getSelectedProvider(), expl.getSelectedModel(),
                expl.getFinalScore(), expl.getComplexityScore(), expl.getReliabilityScore(),
                expl.getCostScore(), summary);
    }

    /**
     * Demo-friendly readable line for execution decisions:
     * {@code [EXECUTION] mode=HYBRID provider=OLLAMA executionType=MOCK reason=...}.
     */
    public void executionHumanLine(String requestId, String mode, String provider,
                                   String executionType, String reason) {
        if (reason == null || reason.isBlank()) {
            log.info("[EXECUTION] requestId={} mode={} provider={} executionType={}",
                    requestId, mode, provider, executionType);
        } else {
            log.info("[EXECUTION] requestId={} mode={} provider={} executionType={} reason=\"{}\"",
                    requestId, mode, provider, executionType, reason);
        }
    }

    /** Single-line auth confirmation for the terminal demo. */
    public void authHumanLine(String requestId, String tenantId) {
        log.info("[AUTH] requestId={} tenant={} authenticated", requestId, tenantId);
    }

    /** Human line on health-driven exclusions or warnings. */
    public void healthHumanLine(String requestId, String provider, String reason, double score) {
        log.warn("[HEALTH] requestId={} provider={} score={} reason=\"{}\"",
                requestId, provider, String.format("%.2f", score), reason);
    }

    /** Human line for fallback decisions. */
    public void fallbackHumanLine(String requestId, String fromProvider, String toProvider, String reason) {
        log.warn("[FALLBACK] requestId={} {} → {} reason=\"{}\"",
                requestId, fromProvider, toProvider, reason);
    }

    /** Human line for quality assessment. */
    public void qualityHumanLine(String requestId, QualitySignals q) {
        if (q == null) return;
        log.info("[QUALITY] requestId={} score={} completeness={} malformed={} note=\"{}\"",
                requestId, String.format("%.1f", q.getScore()), q.getCompleteness(),
                q.isMalformed(), q.getNote());
    }

    /** Compact end-of-request summary that ties everything together. */
    public void completionHumanLine(String requestId, String provider, String model,
                                    long latencyMs, double costUsd, String status,
                                    boolean fallbackUsed) {
        if (fallbackUsed) {
            log.info("[REQUEST COMPLETED] requestId={} provider={} model={} latency={}ms cost=${} status={} fallback=used",
                    requestId, provider, model, latencyMs, formatCost(costUsd), status);
        } else {
            log.info("[REQUEST COMPLETED] requestId={} provider={} model={} latency={}ms cost=${} status={}",
                    requestId, provider, model, latencyMs, formatCost(costUsd), status);
        }
    }

    private String formatCost(double costUsd) {
        return String.format("%.4f", costUsd);
    }
}
