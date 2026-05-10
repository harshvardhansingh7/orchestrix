package com.orchestrix.scenarios;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.ChatResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import com.orchestrix.domain.repository.RequestLogRepository;
import com.orchestrix.exception.BudgetExceededException;
import com.orchestrix.exception.NoProviderAvailableException;
import com.orchestrix.exception.RateLimitExceededException;
import com.orchestrix.mode.ExecutionMode;
import com.orchestrix.mode.ExecutionType;
import com.orchestrix.mode.MockResponseFactory;
import com.orchestrix.mode.ModeService;
import com.orchestrix.mode.ProviderExecutor;
import com.orchestrix.observability.MetricsRecorder;
import com.orchestrix.observability.StructuredLogger;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.resilience.ResilientProviderInvoker;
import com.orchestrix.routing.CostCalculator;
import com.orchestrix.routing.FeatureExtractor;
import com.orchestrix.routing.RoutingEngine;
import com.orchestrix.routing.ScoringService;
import com.orchestrix.service.BudgetService;
import com.orchestrix.service.ChatCompletionService;
import com.orchestrix.service.RateLimitService;
import com.orchestrix.support.ConfigurableTestProvider;
import com.orchestrix.support.NoopCacheService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprehensive scenario matrix:  modes × provider conditions × request shapes.
 *
 * Each test pins one knob (mode, provider state, prompt shape) and asserts the
 * end-to-end response shape and execution decision. Together these cover the
 * test plan published in the API test report.
 */
class ScenarioMatrixTest {

    // ───────────────────────── MOCK mode ───────────────────────────────

    @Nested
    @DisplayName("MOCK mode")
    class MockMode {

        @Test
        void mockModeReturnsMockEvenIfProviderHealthy() {
            ConfigurableTestProvider openai = new ConfigurableTestProvider(ProviderType.OPENAI)
                    .response("would-be-real");
            ConfigurableTestProvider ollama = new ConfigurableTestProvider(ProviderType.OLLAMA)
                    .response("would-be-real");
            ChatCompletionService svc = wire(ExecutionMode.MOCK, openai, ollama);

            ChatResponse r = svc.complete(tenant("acme"), short_("hi")).block();

            assertThat(r.getContent()).startsWith("MOCK:");
            assertThat(r.getExecutionType()).isEqualTo("MOCK");
            assertThat(r.getMode()).isEqualTo("MOCK");
            assertThat(openai.callCount() + ollama.callCount()).isZero();
        }

        @Test
        void mockModeUnaffectedByProviderUnreachable() {
            ConfigurableTestProvider down = new ConfigurableTestProvider(ProviderType.OPENAI)
                    .transientError();
            ChatCompletionService svc = wire(ExecutionMode.MOCK, down);

            ChatResponse r = svc.complete(tenant("acme"), short_("hi")).block();

            assertThat(r.getContent()).startsWith("MOCK:");
            assertThat(r.getStatus()).isEqualTo("completed");
        }

        @Test
        void mockModeStreamingProducesMockChunks() {
            ConfigurableTestProvider p = new ConfigurableTestProvider(ProviderType.OLLAMA);
            ChatCompletionService svc = wire(ExecutionMode.MOCK, p);

            List<ProviderStreamChunk> chunks = svc.stream(tenant("acme"), stream_("hi"))
                    .collectList().block();

            assertThat(chunks).isNotEmpty();
            String full = chunks.stream().map(ProviderStreamChunk::getDelta).reduce("", String::concat);
            assertThat(full).startsWith("MOCK:");
            assertThat(chunks.get(chunks.size() - 1).isDone()).isTrue();
        }
    }

    // ───────────────────────── REAL mode ───────────────────────────────

    @Nested
    @DisplayName("REAL mode")
    class RealMode {

        @Test
        void realModeWithoutCredentialsServesMockWithReason() {
            ConfigurableTestProvider notConfigured = new ConfigurableTestProvider(ProviderType.OPENAI)
                    .configured(false);
            ChatCompletionService svc = wire(ExecutionMode.REAL, notConfigured);

            ChatResponse r = svc.complete(tenant("acme", "OPENAI", "MID"), short_("hi")).block();

            assertThat(r.getExecutionType()).isEqualTo("MOCK");
            assertThat(r.getExecutionReason()).contains("credentials missing");
            assertThat(notConfigured.callCount()).isZero();
        }

        @Test
        void realModeWithCredentialsDelegatesToUpstream() {
            ConfigurableTestProvider working = new ConfigurableTestProvider(ProviderType.OPENAI)
                    .response("real-from-openai");
            ChatCompletionService svc = wire(ExecutionMode.REAL, working);

            ChatResponse r = svc.complete(tenant("acme", "OPENAI", "MID"), short_("hi")).block();

            assertThat(r.getExecutionType()).isEqualTo("REAL");
            assertThat(r.getContent()).isEqualTo("real-from-openai");
        }

        @Test
        void realModeProviderFailureFallsThroughChain() {
            // Ranker picks OLLAMA as primary (free) on a HIGH-tier prompt;
            // when it fails the chain advances to OPENAI as the configured fallback.
            ConfigurableTestProvider failingPrimary = new ConfigurableTestProvider(ProviderType.OLLAMA)
                    .transientError();
            ConfigurableTestProvider workingFallback = new ConfigurableTestProvider(ProviderType.OPENAI)
                    .response("openai-saved-the-day");
            ChatCompletionService svc = wire(ExecutionMode.REAL, failingPrimary, workingFallback);

            ChatResponse r = svc.complete(tenant("acme", "OPENAI,OLLAMA", "HIGH"), arch_()).block();

            assertThat(r.getStatus()).isEqualTo("completed");
            assertThat(r.isFallbackUsed()).isTrue();
            assertThat(r.getProvider()).isEqualTo("OPENAI");
            assertThat(r.getContent()).isEqualTo("openai-saved-the-day");
        }

        @Test
        void realModeAllProvidersFailReturnsError() {
            ConfigurableTestProvider a = new ConfigurableTestProvider(ProviderType.OPENAI).transientError();
            ConfigurableTestProvider b = new ConfigurableTestProvider(ProviderType.OLLAMA).transientError();
            ChatCompletionService svc = wire(ExecutionMode.REAL, a, b);

            assertThatThrownBy(() -> svc.complete(tenant("acme", "OPENAI,OLLAMA", "HIGH"), arch_()).block())
                    .isInstanceOf(NoProviderAvailableException.class);
        }
    }

    // ───────────────────────── HYBRID mode ─────────────────────────────

    @Nested
    @DisplayName("HYBRID mode")
    class HybridMode {

        @Test
        void hybridConvertsProviderFailureIntoMock() {
            ConfigurableTestProvider failing = new ConfigurableTestProvider(ProviderType.OLLAMA)
                    .transientError();
            ChatCompletionService svc = wire(ExecutionMode.HYBRID, failing);

            ChatResponse r = svc.complete(tenant("acme", "OLLAMA", "MID"), short_("hi")).block();

            assertThat(r.getStatus()).isEqualTo("completed");
            assertThat(r.getExecutionType()).isEqualTo("MOCK");
            assertThat(r.getExecutionReason()).contains("real call failed");
            assertThat(r.getContent()).startsWith("MOCK:");
        }

        @Test
        void hybridUpstreamSucceedsReturnsRealContent() {
            ConfigurableTestProvider working = new ConfigurableTestProvider(ProviderType.OPENAI)
                    .response("real-content");
            ChatCompletionService svc = wire(ExecutionMode.HYBRID, working);

            ChatResponse r = svc.complete(tenant("acme", "OPENAI", "HIGH"), arch_()).block();

            assertThat(r.getExecutionType()).isEqualTo("REAL");
            assertThat(r.getContent()).isEqualTo("real-content");
        }

        @Test
        void hybridAllProvidersDownStillSucceedsViaMock() {
            ConfigurableTestProvider a = new ConfigurableTestProvider(ProviderType.OPENAI).transientError();
            ConfigurableTestProvider b = new ConfigurableTestProvider(ProviderType.OLLAMA).transientError();
            ChatCompletionService svc = wire(ExecutionMode.HYBRID, a, b);

            ChatResponse r = svc.complete(tenant("acme", "OPENAI,OLLAMA", "MID"), arch_()).block();

            // First (primary) attempt fails real → mock served. No fallback needed.
            assertThat(r.getStatus()).isEqualTo("completed");
            assertThat(r.getExecutionType()).isEqualTo("MOCK");
        }

        @Test
        void hybridMidStreamFailureDoesNotSpliceMockTokens() {
            // Provider streams 2 real chunks then explodes. HYBRID must NOT
            // splice mock chunks onto the partial real stream — that would
            // corrupt the response. Instead we propagate the error.
            ConfigurableTestProvider midFail = new ConfigurableTestProvider(ProviderType.OPENAI)
                    .midStreamFailureAfter(2)
                    .transientError();
            ChatCompletionService svc = wire(ExecutionMode.HYBRID, midFail);

            assertThatThrownBy(() -> svc.stream(tenant("acme", "OPENAI", "MID"), stream_("hello"))
                    .collectList().block())
                    .satisfiesAnyOf(
                            t -> assertThat(t).hasMessageContaining("transient"),
                            t -> assertThat(t).hasMessageContaining("mid-stream"));
        }

        @Test
        void hybridEarlyStreamFailureFallsBackToMockStream() {
            // Provider errors before any chunk emitted → HYBRID falls back to mock stream.
            ConfigurableTestProvider earlyFail = new ConfigurableTestProvider(ProviderType.OLLAMA)
                    .transientError();
            ChatCompletionService svc = wire(ExecutionMode.HYBRID, earlyFail);

            List<ProviderStreamChunk> chunks = svc.stream(tenant("acme", "OLLAMA", "MID"),
                    stream_("hello")).collectList().block();

            assertThat(chunks).isNotEmpty();
            String all = chunks.stream().map(ProviderStreamChunk::getDelta).reduce("", String::concat);
            assertThat(all).startsWith("MOCK:");
            assertThat(chunks.get(chunks.size() - 1).isDone()).isTrue();
        }
    }

    // ───────────────────── Tenant-policy edge cases ────────────────────

    @Nested
    @DisplayName("Tenant policy")
    class TenantPolicy {

        @Test
        void rateLimitExceededYields429() {
            ConfigurableTestProvider working = new ConfigurableTestProvider(ProviderType.OPENAI);
            ChatCompletionService svc = wire(ExecutionMode.MOCK, working);
            Tenant t = Tenant.builder().tenantId("rl").name("rl").apiKeyHash("h-rl")
                    .dailyBudgetUsd(new BigDecimal("100"))
                    .monthlyBudgetUsd(new BigDecimal("3000"))
                    .rateLimitRps(1).rateLimitRpm(5)
                    .allowedProvidersCsv("OPENAI").maxModelTier("HIGH").enabled(true).build();

            // First request consumes the bucket, second hits the limit.
            svc.complete(t, short_("a")).block();
            assertThatThrownBy(() -> svc.complete(t, short_("b")).block())
                    .isInstanceOf(RateLimitExceededException.class);
        }

        @Test
        void budgetExhaustedReturns402() {
            BudgetService budget = mock(BudgetService.class);
            when(budget.isOverBudget(any())).thenReturn(false);
            when(budget.isNearBudgetLimit(any())).thenReturn(false);
            when(budget.currentDailySpend(any())).thenReturn(BigDecimal.ZERO);
            org.mockito.Mockito.doThrow(new BudgetExceededException("over"))
                    .when(budget).enforceBudget(any());

            ConfigurableTestProvider p = new ConfigurableTestProvider(ProviderType.OPENAI);
            ChatCompletionService svc = wireWithBudget(ExecutionMode.HYBRID, budget, p);

            assertThatThrownBy(() -> svc.complete(tenant("acme", "OPENAI", "MID"), short_("hi")).block())
                    .isInstanceOf(BudgetExceededException.class);
        }

        @Test
        void allowedProvidersFiltersHonored() {
            ConfigurableTestProvider openai = new ConfigurableTestProvider(ProviderType.OPENAI)
                    .response("openai-real");
            ConfigurableTestProvider ollama = new ConfigurableTestProvider(ProviderType.OLLAMA)
                    .response("ollama-real");
            ChatCompletionService svc = wire(ExecutionMode.REAL, openai, ollama);

            // Tenant only allows OLLAMA.
            ChatResponse r = svc.complete(tenant("acme", "OLLAMA", "HIGH"), arch_()).block();

            assertThat(r.getProvider()).isEqualTo("OLLAMA");
            assertThat(openai.callCount()).isZero();
        }
    }

    // ──────────────────────── Routing tier coverage ────────────────────

    @Nested
    @DisplayName("Tier routing")
    class TierRouting {

        @Test
        void shortPromptRoutesToLowTier() {
            ConfigurableTestProvider openai = new ConfigurableTestProvider(ProviderType.OPENAI);
            ConfigurableTestProvider ollama = new ConfigurableTestProvider(ProviderType.OLLAMA);
            ChatCompletionService svc = wire(ExecutionMode.MOCK, openai, ollama);

            ChatResponse r = svc.complete(tenant("acme", "OPENAI,OLLAMA", "HIGH"), short_("hi")).block();
            assertThat(r.getTier()).isEqualTo("LOW");
            assertThat(r.getContent()).contains("LOW tier");
        }

        @Test
        void architecturePromptEscalatesAtLeastToMid() {
            ConfigurableTestProvider openai = new ConfigurableTestProvider(ProviderType.OPENAI);
            ConfigurableTestProvider ollama = new ConfigurableTestProvider(ProviderType.OLLAMA);
            ChatCompletionService svc = wire(ExecutionMode.MOCK, openai, ollama);

            ChatResponse r = svc.complete(tenant("acme", "OPENAI,OLLAMA", "HIGH"), arch_()).block();
            assertThat(r.getTier()).isIn("MID", "HIGH");
        }

        @Test
        void heavyPromptHitsHighTier() {
            ConfigurableTestProvider openai = new ConfigurableTestProvider(ProviderType.OPENAI);
            ConfigurableTestProvider ollama = new ConfigurableTestProvider(ProviderType.OLLAMA);
            ChatCompletionService svc = wire(ExecutionMode.MOCK, openai, ollama);

            String heavy = ("Architect a multi-region distributed kafka-based event-driven "
                    + "high-availability system with CQRS sharding saga patterns. ").repeat(15)
                    + "\n```java\nclass Foo { void bar() { throw new NullPointerException(); } }\n```\n"
                    + "Why is this throwing? Stack trace shows debug needed.";
            ChatResponse r = svc.complete(tenant("acme", "OPENAI,OLLAMA", "HIGH"),
                    ChatRequest.builder().messages(List.of(new ChatMessage("user", heavy))).build()).block();
            assertThat(r.getTier()).isEqualTo("HIGH");
        }
    }

    // ────────────────────── Provider executor unit ────────────────────

    @Nested
    @DisplayName("Provider executor")
    class ExecutorTier {

        @Test
        void executionTypeMatchesActualOutcome() {
            ConfigurableTestProvider failing = new ConfigurableTestProvider(ProviderType.OLLAMA)
                    .transientError();
            ChatCompletionService svc = wire(ExecutionMode.HYBRID, failing);

            ChatResponse r = svc.complete(tenant("acme", "OLLAMA", "MID"), short_("hi")).block();
            assertThat(r.getMode()).isEqualTo("HYBRID");
            assertThat(r.getExecutionType()).isEqualTo("MOCK");
        }
    }

    // ────────────────────────── Wiring helpers ─────────────────────────

    private ChatCompletionService wire(ExecutionMode mode, ConfigurableTestProvider... providers) {
        BudgetService budget = mock(BudgetService.class);
        when(budget.isOverBudget(any())).thenReturn(false);
        when(budget.isNearBudgetLimit(any())).thenReturn(false);
        when(budget.currentDailySpend(any())).thenReturn(BigDecimal.ZERO);
        return wireWithBudget(mode, budget, providers);
    }

    private ChatCompletionService wireWithBudget(ExecutionMode mode, BudgetService budget,
                                                  ConfigurableTestProvider... providers) {
        OrchestrixProperties props = new OrchestrixProperties();
        props.setProviders(Map.of(
                "openai", providerCfg(0.5, 1.5),
                "ollama", providerCfg(0.0, 0.0),
                "anthropic", providerCfg(3.0, 15.0)));
        props.setFallbackChain(List.of(ProviderType.OPENAI, ProviderType.ANTHROPIC, ProviderType.OLLAMA));
        props.getResilience().setMaxRetries(1);
        props.getResilience().setRetryBaseBackoffMs(1);
        props.getResilience().setRequestTimeoutMs(2000);
        props.setMode(mode.name());

        ProviderRegistry registry = new ProviderRegistry(List.of(providers));
        return com.orchestrix.support.Wiring.completion(props, registry, budget, new NoopCacheService());
    }

    private static OrchestrixProperties.Provider providerCfg(double in, double out) {
        OrchestrixProperties.Provider p = new OrchestrixProperties.Provider();
        p.setEnabled(true);
        p.setBaseUrl("http://localhost");
        p.setApiKey("test");
        p.setCostPer1kInputTokens(in);
        p.setCostPer1kOutputTokens(out);
        p.setModels(Map.of("low", "low", "mid", "mid", "high", "high"));
        return p;
    }

    private static Tenant tenant(String id) {
        return tenant(id, "OPENAI,OLLAMA,ANTHROPIC", "HIGH");
    }

    private static Tenant tenant(String id, String allowed, String maxTier) {
        return Tenant.builder()
                .tenantId(id).name(id).apiKeyHash("h-" + id)
                .dailyBudgetUsd(new BigDecimal("100"))
                .monthlyBudgetUsd(new BigDecimal("3000"))
                .rateLimitRps(50).rateLimitRpm(500)
                .allowedProvidersCsv(allowed).maxModelTier(maxTier).enabled(true).build();
    }

    private static ChatRequest short_(String content) {
        return ChatRequest.builder().messages(List.of(new ChatMessage("user", content))).build();
    }

    private static ChatRequest stream_(String content) {
        return ChatRequest.builder()
                .messages(List.of(new ChatMessage("user", content)))
                .stream(true)
                .build();
    }

    private static ChatRequest arch_() {
        return ChatRequest.builder()
                .messages(List.of(new ChatMessage("user",
                        "Design a high-availability distributed event-driven kafka architecture with sharding")))
                .build();
    }
}
