package com.orchestrix.service;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.ChatResponse;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.support.NoopCacheService;
import com.orchestrix.support.TestLLMProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end orchestration test wired with in-memory test providers.
 * Verifies the fallback chain triggers when the primary provider keeps failing,
 * and that the second provider successfully serves the response.
 *
 * Mode is forced to REAL so the legacy fallback semantics are preserved
 * (HYBRID would convert the first failure to a mock and never invoke fallback).
 */
class ChatCompletionFallbackTest {

    @Test
    void primaryFailureFallsBackToSecondaryProvider() {
        OrchestrixProperties props = baseProps();

        // Ranker picks OLLAMA as primary (free); failing it forces the
        // orchestrator to fall through to OPENAI as the working fallback.
        TestLLMProvider failingPrimary = new TestLLMProvider(ProviderType.OLLAMA, true, 10, "ollama-response");
        TestLLMProvider workingFallback = new TestLLMProvider(ProviderType.OPENAI, true, 0, "openai-response");
        ProviderRegistry registry = new ProviderRegistry(List.of(failingPrimary, workingFallback));

        ChatCompletionService svc = wire(props, registry);

        Tenant t = tenant("acme", "OPENAI,OLLAMA", "HIGH");
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(new ChatMessage("user",
                        "Design a complex distributed kafka-based event-driven architecture with sharding")))
                .build();

        ChatResponse resp = svc.complete(t, req).block();

        assertThat(resp).isNotNull();
        assertThat(resp.getStatus()).isEqualTo("completed");
        assertThat(resp.isFallbackUsed()).isTrue();
        assertThat(resp.getProvider()).isEqualTo("OPENAI");
        assertThat(resp.getContent()).isEqualTo("openai-response");
        assertThat(resp.getFallbackChain()).contains("OLLAMA").contains("OPENAI");
    }

    @Test
    void allProvidersFailingPropagatesError() {
        OrchestrixProperties props = baseProps();

        TestLLMProvider openai = new TestLLMProvider(ProviderType.OPENAI, true, 99, "x");
        TestLLMProvider ollama = new TestLLMProvider(ProviderType.OLLAMA, true, 99, "x");
        ProviderRegistry registry = new ProviderRegistry(List.of(openai, ollama));

        ChatCompletionService svc = wire(props, registry);
        Tenant t = tenant("acme", "OPENAI,OLLAMA", "HIGH");
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(new ChatMessage("user", "hello")))
                .build();

        ChatResponse outcome = svc.complete(t, req)
                .onErrorReturn(ChatResponse.builder().status("error").build())
                .block();
        assertThat(outcome).isNotNull();
        assertThat(outcome.getStatus()).isEqualTo("error");
    }

    private ChatCompletionService wire(OrchestrixProperties props, ProviderRegistry registry) {
        // Force REAL mode for these tests so the existing fallback-chain
        // semantics apply. HYBRID is exercised by ProviderExecutorTest.
        props.setMode("REAL");

        BudgetService budget = mock(BudgetService.class);
        when(budget.isOverBudget(any())).thenReturn(false);
        when(budget.isNearBudgetLimit(any())).thenReturn(false);
        when(budget.currentDailySpend(any())).thenReturn(BigDecimal.ZERO);

        return com.orchestrix.support.Wiring.completion(props, registry, budget, new NoopCacheService());
    }

    private OrchestrixProperties baseProps() {
        OrchestrixProperties props = new OrchestrixProperties();
        props.setProviders(Map.of(
                "openai", provider(0.5, 1.5),
                "ollama", provider(0.0, 0.0)
        ));
        props.setFallbackChain(List.of(ProviderType.OPENAI, ProviderType.OLLAMA));
        props.getResilience().setMaxRetries(2);
        props.getResilience().setRetryBaseBackoffMs(10);
        props.getResilience().setRequestTimeoutMs(2000);
        return props;
    }

    private static OrchestrixProperties.Provider provider(double inCost, double outCost) {
        OrchestrixProperties.Provider p = new OrchestrixProperties.Provider();
        p.setEnabled(true);
        p.setBaseUrl("http://localhost");
        p.setApiKey("test");
        p.setCostPer1kInputTokens(inCost);
        p.setCostPer1kOutputTokens(outCost);
        p.setModels(Map.of("low", "low", "mid", "mid", "high", "high"));
        return p;
    }

    private static Tenant tenant(String id, String allowed, String maxTier) {
        return Tenant.builder()
                .tenantId(id).name(id).apiKeyHash("h-" + id)
                .dailyBudgetUsd(new BigDecimal("100"))
                .monthlyBudgetUsd(new BigDecimal("3000"))
                .rateLimitRps(10).rateLimitRpm(100)
                .allowedProvidersCsv(allowed).maxModelTier(maxTier).enabled(true)
                .build();
    }
}
