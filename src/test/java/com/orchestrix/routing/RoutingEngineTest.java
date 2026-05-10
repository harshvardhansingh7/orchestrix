package com.orchestrix.routing;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.ModelTier;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.model.RoutingDecision;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.service.BudgetService;
import com.orchestrix.support.TestLLMProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingEngineTest {

    private RoutingEngine engine;
    private ProviderHealthTracker healthTracker;
    private BudgetService budgetService;

    @BeforeEach
    void setUp() {
        OrchestrixProperties props = new OrchestrixProperties();
        props.setProviders(Map.of(
                "openai", provider(0.5, 1.5),
                "ollama", provider(0.0, 0.0),
                "anthropic", provider(3.0, 15.0)
        ));
        props.setFallbackChain(List.of(ProviderType.OPENAI, ProviderType.ANTHROPIC, ProviderType.OLLAMA));

        FeatureExtractor extractor = new FeatureExtractor();
        CostCalculator cost = new CostCalculator(props);

        healthTracker = new ProviderHealthTracker(mock(ProviderHealthRepository.class),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()));
        healthTracker.init();

        ScoringService scoring = new ScoringService(props, healthTracker, cost);

        ProviderRegistry registry = new ProviderRegistry(List.of(
                new TestLLMProvider(ProviderType.OPENAI, true, 0, "ok"),
                new TestLLMProvider(ProviderType.OLLAMA, true, 0, "ok"),
                new TestLLMProvider(ProviderType.ANTHROPIC, true, 0, "ok")
        ));

        budgetService = mock(BudgetService.class);
        when(budgetService.isOverBudget(any())).thenReturn(false);
        when(budgetService.isNearBudgetLimit(any())).thenReturn(false);

        engine = new RoutingEngine(extractor, scoring, registry, healthTracker, budgetService, props);
    }

    @Test
    void simpleShortPromptRoutesToLowTier() {
        Tenant t = tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "HIGH");
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(new ChatMessage("user", "hi")))
                .build();

        RoutingDecision d = engine.route(t, req);

        assertThat(d.getSelectedTier()).isEqualTo(ModelTier.LOW);
        // Low tier should prefer Ollama when available.
        assertThat(d.getPrimaryProvider()).isEqualTo(ProviderType.OLLAMA);
        assertThat(d.getFallbackProviders()).contains(ProviderType.OPENAI);
    }

    @Test
    void architectureQueryEscalatesToHighTier() {
        Tenant t = tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "HIGH");
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(new ChatMessage("user",
                        "Design a high-availability distributed event-driven architecture using kafka with sharding and CQRS")))
                .build();

        RoutingDecision d = engine.route(t, req);
        assertThat(d.getSelectedTier()).isIn(ModelTier.MID, ModelTier.HIGH);
    }

    @Test
    void tenantTierCeilingIsHonored() {
        Tenant t = tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "LOW");
        // Pile on every signal so the natural score lands well above LOW.
        String big = "Architect a multi-region distributed event-driven kafka-based "
                + "high-availability system with CQRS, sharding and saga patterns. ".repeat(20)
                + "\n```java\nclass Foo { void bar() { throw new NullPointerException(); } }\n```\n"
                + "Why is this throwing? Stack trace says NullPointerException.";
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(new ChatMessage("user", big)))
                .build();

        RoutingDecision d = engine.route(t, req);
        assertThat(d.getSelectedTier()).isEqualTo(ModelTier.LOW);
        assertThat(d.isOverrideApplied()).isTrue();
        assertThat(d.getOverrideReason()).contains("tenant-tier-ceiling");
    }

    @Test
    void overBudgetForcesLowTier() {
        when(budgetService.isOverBudget(any())).thenReturn(true);
        Tenant t = tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "HIGH");
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(new ChatMessage("user",
                        "Very long architecture design question with debugging complexity")))
                .build();

        RoutingDecision d = engine.route(t, req);
        assertThat(d.getSelectedTier()).isEqualTo(ModelTier.LOW);
        assertThat(d.getOverrideReason()).contains("over-daily-budget");
    }

    @Test
    void allowedProvidersFilterIsRespected() {
        Tenant t = tenant("acme", "OLLAMA", "HIGH");
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(new ChatMessage("user", "hi")))
                .build();

        RoutingDecision d = engine.route(t, req);
        assertThat(d.getPrimaryProvider()).isEqualTo(ProviderType.OLLAMA);
        assertThat(d.getFallbackProviders()).isEmpty();
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
                .tenantId(id)
                .name(id)
                .apiKeyHash("h-" + id)
                .dailyBudgetUsd(new BigDecimal("100.0000"))
                .monthlyBudgetUsd(new BigDecimal("3000.0000"))
                .rateLimitRps(10)
                .rateLimitRpm(100)
                .allowedProvidersCsv(allowed)
                .maxModelTier(maxTier)
                .enabled(true)
                .build();
    }

    @SuppressWarnings("unused")
    private static LLMProvider unused() { return null; } // kept to silence import lint
}
