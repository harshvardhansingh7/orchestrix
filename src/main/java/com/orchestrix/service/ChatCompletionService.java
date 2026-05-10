package com.orchestrix.service;

import com.orchestrix.domain.entity.RequestLog;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.ChatResponse;
import com.orchestrix.domain.model.ProviderInvocation;
import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.model.RoutingDecision;
import com.orchestrix.domain.repository.RequestLogRepository;
import com.orchestrix.exception.NoProviderAvailableException;
import com.orchestrix.exception.ProviderException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.mode.ExecutionResult;
import com.orchestrix.mode.ExecutionType;
import com.orchestrix.mode.ModeService;
import com.orchestrix.mode.ProviderExecutor;
import com.orchestrix.observability.MetricsRecorder;
import com.orchestrix.observability.StructuredLogger;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.quality.QualityEvaluator;
import com.orchestrix.quality.QualitySignals;
import com.orchestrix.quality.ProviderQualityHistory;
import com.orchestrix.routing.CostCalculator;
import com.orchestrix.routing.RoutingEngine;
import com.orchestrix.routing.RoutingExplanation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-level orchestrator for chat completions.
 *
 * Lifecycle for a single request:
 *   1. Authenticate (filter sets TenantContext).
 *   2. Rate-limit the tenant.
 *   3. Enforce hard daily-budget cap (over-budget -> 402).
 *   4. Cache lookup (non-streaming only).
 *   5. RoutingEngine picks primary + ordered fallbacks.
 *   6. For each provider in chain: ProviderExecutor decides real vs mock per
 *      the active ExecutionMode; resilience layer handles retry + circuit breaker
 *      around real calls.
 *   7. Record cost / metrics / structured log / persisted RequestLog row.
 *
 * Routing logic, scoring, fallback chain, tenant isolation, and rate limits
 * are unchanged from the original orchestrator — only the leaf "execute this
 * attempt" step is now mode-aware via {@link ProviderExecutor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCompletionService {

    private final RoutingEngine routingEngine;
    private final ProviderRegistry providerRegistry;
    private final ProviderExecutor providerExecutor;
    private final ModeService modeService;
    private final RateLimitService rateLimitService;
    private final BudgetService budgetService;
    private final CacheService cacheService;
    private final CostCalculator costCalculator;
    private final RequestLogRepository requestLogRepository;
    private final StructuredLogger structuredLogger;
    private final MetricsRecorder metrics;
    private final QualityEvaluator qualityEvaluator;
    private final ProviderQualityHistory qualityHistory;
    private final ObjectMapper objectMapper;

    /** Non-streaming entry point. */
    public Mono<ChatResponse> complete(Tenant tenant, ChatRequest request) {
        long startMs = System.currentTimeMillis();
        String requestId = MDC.get("requestId");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            MDC.put("requestId", requestId);
        }
        final String reqId = requestId;

        rateLimitService.enforce(tenant);
        budgetService.enforceBudget(tenant);

        RoutingDecision decision = routingEngine.route(tenant, request);
        logRoutingDecision(reqId, tenant, decision);
        metrics.recordRoutingScore(tenant.getTenantId(), decision.getSelectedTier().name(),
                decision.getFinalScore());

        // Cache lookup using tier as part of the key so two tiers don't collide.
        var cached = cacheService.get(tenant, request, decision.getSelectedTier().name());
        if (cached.isPresent()) {
            ChatResponse hit = cached.get();
            hit.setRequestId(reqId);
            hit.setCacheHit(true);
            hit.setStatus("completed");
            metrics.recordCacheHit(tenant.getTenantId());
            metrics.recordRequest(tenant.getTenantId(), hit.getProvider(), hit.getModel(),
                    "completed", hit.getLatencyMs() == null ? 0 : hit.getLatencyMs(),
                    hit.getTotalTokens() == null ? 0 : hit.getTotalTokens(),
                    0.0, true, false);
            persistRequestLog(reqId, tenant, decision, hit, true, null, false);
            return Mono.just(hit);
        }

        List<ProviderType> chain = providerOrder(decision);

        return tryNext(reqId, tenant, request, decision, chain, 0, new ArrayList<>(), startMs);
    }

    /** Streaming entry point — returns ordered token chunks. */
    public Flux<ProviderStreamChunk> stream(Tenant tenant, ChatRequest request) {
        String requestId = MDC.get("requestId");
        if (requestId == null) requestId = UUID.randomUUID().toString();
        final String reqId = requestId;

        rateLimitService.enforce(tenant);
        budgetService.enforceBudget(tenant);

        RoutingDecision decision = routingEngine.route(tenant, request);
        logRoutingDecision(reqId, tenant, decision);
        metrics.recordRoutingScore(tenant.getTenantId(), decision.getSelectedTier().name(),
                decision.getFinalScore());

        long start = System.currentTimeMillis();
        List<ProviderType> chain = providerOrder(decision);

        // Streaming attempts the primary; on early failure (no bytes yet) we
        // fall back to the next provider. Once tokens have started flowing,
        // a mid-stream failure marks the response as "incomplete_stream"
        // rather than restarting (we'd otherwise emit duplicate tokens).
        return streamWithFallback(reqId, tenant, request, decision, chain, 0,
                new AtomicInteger(0), new ArrayList<>(), start);
    }

    private Mono<ChatResponse> tryNext(String requestId, Tenant tenant, ChatRequest request,
                                       RoutingDecision decision, List<ProviderType> chain, int idx,
                                       List<String> attemptedSoFar, long startMs) {
        if (idx >= chain.size()) {
            ChatResponse resp = ChatResponse.builder()
                    .requestId(requestId)
                    .status("error")
                    .errorMessage("all providers failed")
                    .latencyMs(System.currentTimeMillis() - startMs)
                    .fallbackUsed(attemptedSoFar.size() > 1)
                    .fallbackChain(String.join("->", attemptedSoFar))
                    .tier(decision.getSelectedTier().name())
                    .mode(modeService.current().name())
                    .routing(decision.getExplanation())
                    .build();
            persistRequestLog(requestId, tenant, decision, resp, false, "all providers failed", false);
            metrics.recordRequest(tenant.getTenantId(), "none", "none", "error",
                    resp.getLatencyMs(), 0, 0.0, false, true);
            return Mono.error(new NoProviderAvailableException("all providers failed for tenant "
                    + tenant.getTenantId()));
        }

        ProviderType providerType = chain.get(idx);
        LLMProvider provider = providerRegistry.get(providerType)
                .orElseThrow(() -> new NoProviderAvailableException("provider missing: " + providerType));
        String model = idx == 0 ? decision.getPrimaryModel()
                : provider.resolveModel(decision.getSelectedTier().name().toLowerCase());

        ProviderInvocation invocation = ProviderInvocation.builder()
                .requestId(requestId)
                .tenantId(tenant.getTenantId())
                .provider(providerType)
                .model(model)
                .messages(request.getMessages())
                .maxTokens(request.getMaxTokens())
                .temperature(request.getTemperature())
                .build();

        attemptedSoFar.add(providerType.name());

        return providerExecutor.execute(provider, invocation, decision.getSelectedTier())
                .flatMap(execResult -> {
                    ProviderResponse pr = execResult.getResponse();
                    structuredLogger.executionHumanLine(requestId, modeService.current().name(),
                            providerType.name(), execResult.getExecutionType().name(),
                            execResult.getReason());
                    ChatResponse resp = toResponse(requestId, decision, providerType, model, pr,
                            attemptedSoFar, startMs, execResult);
                    // Only cache responses produced from real upstream calls. Mock responses
                    // are cheap to regenerate and would otherwise survive a mode flip and
                    // serve stale mock content after a switch to REAL/HYBRID.
                    if (execResult.getExecutionType() == ExecutionType.REAL) {
                        cacheService.put(tenant, request, decision.getSelectedTier().name(), resp);
                    }
                    BigDecimal cost = execResult.getExecutionType() == ExecutionType.MOCK
                            ? BigDecimal.ZERO
                            : costCalculator.exactCostUsd(providerType,
                                    pr.getPromptTokens(), pr.getCompletionTokens());
                    budgetService.recordSpend(tenant, cost,
                            (long) (pr.getPromptTokens() + pr.getCompletionTokens()));
                    metrics.recordRequest(tenant.getTenantId(), providerType.name(), model, "completed",
                            resp.getLatencyMs(), resp.getTotalTokens() == null ? 0 : resp.getTotalTokens(),
                            cost.doubleValue(), false, attemptedSoFar.size() > 1);
                    structuredLogger.requestCompleted(requestId, tenant.getTenantId(),
                            providerType.name(), model, resp.getLatencyMs(),
                            pr.getPromptTokens(), pr.getCompletionTokens(),
                            pr.getPromptTokens() + pr.getCompletionTokens(),
                            cost.doubleValue(), decision.getFinalScore(),
                            attemptedSoFar.size() > 1, String.join("->", attemptedSoFar),
                            false, "completed");
                    structuredLogger.completionHumanLine(requestId, providerType.name(), model,
                            resp.getLatencyMs() == null ? 0 : resp.getLatencyMs(),
                            cost.doubleValue(), "completed", attemptedSoFar.size() > 1);
                    persistRequestLog(requestId, tenant, decision, resp, false, null, false);
                    return Mono.just(resp);
                })
                .onErrorResume(err -> {
                    log.warn("provider attempt failed provider={} requestId={} err={}",
                            providerType, requestId, err.getMessage());
                    if (idx + 1 < chain.size()) {
                        ProviderType nextType = chain.get(idx + 1);
                        structuredLogger.fallbackUsed(requestId, tenant.getTenantId(),
                                providerType.name(), nextType.name(), errorClass(err));
                        structuredLogger.fallbackHumanLine(requestId, providerType.name(),
                                nextType.name(), errorClass(err));
                        metrics.recordFallback(tenant.getTenantId(), providerType.name(), nextType.name());
                    }
                    return tryNext(requestId, tenant, request, decision, chain, idx + 1,
                            attemptedSoFar, startMs);
                });
    }

    private Flux<ProviderStreamChunk> streamWithFallback(String requestId, Tenant tenant,
                                                          ChatRequest request, RoutingDecision decision,
                                                          List<ProviderType> chain, int idx,
                                                          AtomicInteger emittedChunks,
                                                          List<String> attemptedSoFar, long startMs) {
        if (idx >= chain.size()) {
            return Flux.error(new NoProviderAvailableException("all providers failed for tenant "
                    + tenant.getTenantId()));
        }
        ProviderType providerType = chain.get(idx);
        LLMProvider provider = providerRegistry.get(providerType)
                .orElseThrow(() -> new NoProviderAvailableException("provider missing: " + providerType));
        String model = idx == 0 ? decision.getPrimaryModel()
                : provider.resolveModel(decision.getSelectedTier().name().toLowerCase());
        attemptedSoFar.add(providerType.name());

        ProviderInvocation invocation = ProviderInvocation.builder()
                .requestId(requestId)
                .tenantId(tenant.getTenantId())
                .provider(providerType)
                .model(model)
                .messages(request.getMessages())
                .maxTokens(request.getMaxTokens())
                .temperature(request.getTemperature())
                .build();

        // Mutable holder so the doOnComplete handler can read final usage.
        StringBuilder collected = new StringBuilder();
        int[] tokens = {0, 0}; // [prompt, completion]
        ExecutionType[] executionType = {ExecutionType.REAL};
        String[] executionReason = {null};

        return providerExecutor.executeStream(provider, invocation, decision.getSelectedTier())
                .doOnNext(se -> {
                    ProviderStreamChunk chunk = se.chunk();
                    executionType[0] = se.executionType();
                    if (se.reason() != null) executionReason[0] = se.reason();
                    emittedChunks.incrementAndGet();
                    if (chunk.getDelta() != null) collected.append(chunk.getDelta());
                    if (chunk.isDone()) {
                        if (chunk.getPromptTokens() != null) tokens[0] = chunk.getPromptTokens();
                        if (chunk.getCompletionTokens() != null) tokens[1] = chunk.getCompletionTokens();
                    }
                })
                .map(ProviderExecutorChunkAdapter::chunk)
                .doOnComplete(() -> {
                    long latency = System.currentTimeMillis() - startMs;
                    structuredLogger.executionHumanLine(requestId, modeService.current().name(),
                            providerType.name(), executionType[0].name(), executionReason[0]);
                    BigDecimal cost = executionType[0] == ExecutionType.MOCK
                            ? BigDecimal.ZERO
                            : costCalculator.exactCostUsd(providerType, tokens[0], tokens[1]);
                    budgetService.recordSpend(tenant, cost, (long) (tokens[0] + tokens[1]));
                    metrics.recordRequest(tenant.getTenantId(), providerType.name(), model, "completed",
                            latency, tokens[0] + tokens[1], cost.doubleValue(), false, attemptedSoFar.size() > 1);
                    structuredLogger.requestCompleted(requestId, tenant.getTenantId(),
                            providerType.name(), model, latency, tokens[0], tokens[1],
                            tokens[0] + tokens[1], cost.doubleValue(), decision.getFinalScore(),
                            attemptedSoFar.size() > 1, String.join("->", attemptedSoFar),
                            false, "completed");
                    ProviderResponse syntheticForQuality = ProviderResponse.builder()
                            .content(collected.toString())
                            .promptTokens(tokens[0])
                            .completionTokens(tokens[1])
                            .latencyMs(latency)
                            .model(model)
                            .provider(providerType)
                            .build();
                    QualitySignals quality = qualityEvaluator.evaluate(providerType, syntheticForQuality,
                            tokens[0], false);
                    qualityHistory.record(providerType, quality.getScore());
                    structuredLogger.qualityHumanLine(requestId, quality);

                    ChatResponse resp = ChatResponse.builder()
                            .requestId(requestId)
                            .content(collected.toString())
                            .provider(providerType.name())
                            .model(model)
                            .promptTokens(tokens[0])
                            .completionTokens(tokens[1])
                            .totalTokens(tokens[0] + tokens[1])
                            .costUsd(cost.toPlainString())
                            .latencyMs(latency)
                            .fallbackUsed(attemptedSoFar.size() > 1)
                            .fallbackChain(String.join("->", attemptedSoFar))
                            .status("completed")
                            .streamed(true)
                            .tier(decision.getSelectedTier().name())
                            .mode(modeService.current().name())
                            .executionType(executionType[0].name())
                            .executionReason(executionReason[0])
                            .routing(decision.getExplanation())
                            .qualityScore(quality.getScore())
                            .qualityNote(quality.getNote())
                            .build();
                    persistRequestLog(requestId, tenant, decision, resp, false, null, true);
                })
                .onErrorResume(err -> {
                    if (emittedChunks.get() == 0 && idx + 1 < chain.size()) {
                        // Fallback only if no tokens emitted yet — otherwise client would see
                        // a corrupted stream. Mid-stream failures end the stream with an error.
                        ProviderType nextType = chain.get(idx + 1);
                        structuredLogger.fallbackUsed(requestId, tenant.getTenantId(),
                                providerType.name(), nextType.name(), errorClass(err));
                        structuredLogger.fallbackHumanLine(requestId, providerType.name(),
                                nextType.name(), errorClass(err));
                        metrics.recordFallback(tenant.getTenantId(), providerType.name(), nextType.name());
                        return streamWithFallback(requestId, tenant, request, decision, chain,
                                idx + 1, emittedChunks, attemptedSoFar, startMs);
                    }
                    long latency = System.currentTimeMillis() - startMs;
                    String status = emittedChunks.get() > 0 ? "incomplete_stream" : "error";
                    ChatResponse resp = ChatResponse.builder()
                            .requestId(requestId)
                            .provider(providerType.name())
                            .model(model)
                            .latencyMs(latency)
                            .status(status)
                            .errorMessage(err.getMessage())
                            .streamed(true)
                            .fallbackUsed(attemptedSoFar.size() > 1)
                            .fallbackChain(String.join("->", attemptedSoFar))
                            .tier(decision.getSelectedTier().name())
                            .mode(modeService.current().name())
                            .routing(decision.getExplanation())
                            .build();
                    metrics.recordRequest(tenant.getTenantId(), providerType.name(), model, status,
                            latency, 0, 0.0, false, attemptedSoFar.size() > 1);
                    persistRequestLog(requestId, tenant, decision, resp, false, err.getMessage(), true);
                    return Flux.error(err);
                });
    }

    /** Static helper: extract the chunk out of a ProviderExecutor.StreamExecution. */
    static class ProviderExecutorChunkAdapter {
        static ProviderStreamChunk chunk(ProviderExecutor.StreamExecution se) {
            return se.chunk();
        }
    }

    private void logRoutingDecision(String requestId, Tenant tenant, RoutingDecision decision) {
        structuredLogger.routingDecisionLogged(requestId, tenant.getTenantId(),
                decision.getSelectedTier().name(),
                decision.getComplexityScore(), decision.getCostScore(),
                decision.getReliabilityScore(), decision.getFinalScore(),
                decision.isOverrideApplied(), decision.getOverrideReason());
        // The explained line replaces the old short routing line — same prefix
        // so existing log greps continue to work, with the reasoning summary added.
        if (decision.getExplanation() != null) {
            structuredLogger.routingExplainedLine(requestId, decision.getExplanation());
        } else {
            structuredLogger.routingHumanLine(requestId, decision.getSelectedTier().name(),
                    decision.getFinalScore(), decision.getPrimaryProvider().name());
        }
    }

    private List<ProviderType> providerOrder(RoutingDecision decision) {
        List<ProviderType> chain = new ArrayList<>();
        chain.add(decision.getPrimaryProvider());
        chain.addAll(decision.getFallbackProviders());
        return chain;
    }

    private ChatResponse toResponse(String requestId, RoutingDecision decision, ProviderType provider,
                                    String model, ProviderResponse pr, List<String> attempted,
                                    long startMs, ExecutionResult execResult) {
        BigDecimal cost = execResult.getExecutionType() == ExecutionType.MOCK
                ? BigDecimal.ZERO
                : costCalculator.exactCostUsd(provider, pr.getPromptTokens(), pr.getCompletionTokens());

        QualitySignals quality = qualityEvaluator.evaluate(provider, pr, pr.getPromptTokens(), false);
        qualityHistory.record(provider, quality.getScore());
        structuredLogger.qualityHumanLine(requestId, quality);

        return ChatResponse.builder()
                .requestId(requestId)
                .content(pr.getContent())
                .provider(provider.name())
                .model(model)
                .promptTokens(pr.getPromptTokens())
                .completionTokens(pr.getCompletionTokens())
                .totalTokens(pr.getPromptTokens() + pr.getCompletionTokens())
                .costUsd(cost.toPlainString())
                .latencyMs(System.currentTimeMillis() - startMs)
                .fallbackUsed(attempted.size() > 1)
                .fallbackChain(String.join("->", attempted))
                .status("completed")
                .tier(decision.getSelectedTier().name())
                .mode(modeService.current().name())
                .executionType(execResult.getExecutionType().name())
                .executionReason(execResult.getReason())
                .routing(decision.getExplanation())
                .qualityScore(quality.getScore())
                .qualityNote(quality.getNote())
                .build();
    }

    private void persistRequestLog(String requestId, Tenant tenant, RoutingDecision decision,
                                   ChatResponse resp, boolean cacheHit, String error, boolean streamed) {
        try {
            RoutingExplanation expl = resp.getRouting();
            String reasoningCsv = null;
            String explainJson = null;
            if (expl != null) {
                if (expl.getReasoning() != null) {
                    String joined = String.join(",", expl.getReasoning());
                    reasoningCsv = joined.length() > 2000 ? joined.substring(0, 2000) : joined;
                }
                try {
                    explainJson = objectMapper.writeValueAsString(expl);
                } catch (JsonProcessingException ignored) {
                    explainJson = null;
                }
            }
            RequestLog rl = RequestLog.builder()
                    .requestId(requestId)
                    .tenantId(tenant.getTenantId())
                    .provider(resp.getProvider() == null ? "none" : resp.getProvider())
                    .model(resp.getModel() == null ? "none" : resp.getModel())
                    .routingScore(decision == null ? null : decision.getFinalScore())
                    .complexityScore(decision == null ? null : decision.getComplexityScore())
                    .costScore(decision == null ? null : decision.getCostScore())
                    .reliabilityScore(decision == null ? null : decision.getReliabilityScore())
                    .promptTokens(resp.getPromptTokens())
                    .completionTokens(resp.getCompletionTokens())
                    .totalTokens(resp.getTotalTokens())
                    .costUsd(resp.getCostUsd() == null ? null : new BigDecimal(resp.getCostUsd()))
                    .latencyMs(resp.getLatencyMs())
                    .status(resp.getStatus() == null ? "unknown" : resp.getStatus())
                    .fallbackUsed(resp.isFallbackUsed())
                    .fallbackChain(resp.getFallbackChain())
                    .errorMessage(error == null ? resp.getErrorMessage() : error)
                    .streamed(streamed)
                    .cacheHit(cacheHit)
                    .mode(resp.getMode())
                    .executionType(resp.getExecutionType())
                    .executionReason(resp.getExecutionReason())
                    .routingReasoning(reasoningCsv)
                    .routingExplanationJson(explainJson)
                    .qualityScore(resp.getQualityScore())
                    .qualityNote(resp.getQualityNote())
                    .build();
            requestLogRepository.save(rl);
        } catch (Exception e) {
            // Logging persistence must not break the user-facing response.
            log.warn("failed to persist request log requestId={} err={}", requestId, e.getMessage());
        }
    }

    private String errorClass(Throwable t) {
        if (t instanceof ProviderException pe) {
            return pe.getProvider() + ":" + (pe.getMessage() == null ? "error" : pe.getMessage());
        }
        return t.getClass().getSimpleName();
    }

    public List<ProviderType> chainSummary(RoutingDecision d) {
        return providerOrder(d);
    }

    public List<RequestLog> recentLogsForTenant(String tenantId) {
        return requestLogRepository.findTop50ByTenantIdOrderByCreatedAtDesc(tenantId);
    }
}
