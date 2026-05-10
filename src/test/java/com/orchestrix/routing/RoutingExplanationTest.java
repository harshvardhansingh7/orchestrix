package com.orchestrix.routing;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.model.RoutingDecision;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.quality.ProviderQualityHistory;
import com.orchestrix.service.BudgetService;
import com.orchestrix.support.TestLLMProvider;
import com.orchestrix.support.Wiring;
import com.orchestrix.tokenizer.TokenEstimatorRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the routing explanation is populated with the expected reasoning
 * tags for representative scenarios.
 */
class RoutingExplanationTest {

    @Test
    void architecturePromptIncludesArchitectureTag() {
        RoutingDecision d = engine().route(tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "HIGH"),
                ChatRequest.builder()
                        .messages(List.of(new ChatMessage("user",
                                "Design a high-availability distributed kafka architecture with sharding")))
                        .build());

        RoutingExplanation expl = d.getExplanation();
        assertThat(expl).isNotNull();
        assertThat(expl.getReasoning()).contains("architecture_keywords_detected");
        assertThat(expl.getSelectedTier()).isIn("MID", "HIGH");
        assertThat(expl.getSelectedProvider()).isNotNull();
        assertThat(expl.getCandidates()).isNotEmpty();
    }

    @Test
    void codePromptIncludesCodeTag() {
        RoutingDecision d = engine().route(tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "HIGH"),
                ChatRequest.builder()
                        .messages(List.of(new ChatMessage("user",
                                "Why does this not work?\n```java\nclass Foo {}\n```")))
                        .build());

        assertThat(d.getExplanation().getReasoning()).contains("code_block_detected");
    }

    @Test
    void debuggingPromptIncludesDebugTag() {
        RoutingDecision d = engine().route(tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "HIGH"),
                ChatRequest.builder()
                        .messages(List.of(new ChatMessage("user",
                                "Why is this throwing NullPointerException?")))
                        .build());
        assertThat(d.getExplanation().getReasoning()).contains("debugging_keywords_detected");
    }

    @Test
    void tenantTierCeilingProducesOverrideTag() {
        RoutingDecision d = engine().route(tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "LOW"),
                ChatRequest.builder()
                        .messages(List.of(new ChatMessage("user",
                                ("Architect a distributed kafka system with CQRS sharding. ".repeat(15))
                                        + "\n```java\nthrow new NullPointerException();\n```")))
                        .build());
        assertThat(d.getExplanation().getReasoning()).anyMatch(s -> s.contains("override_tenant_tier_ceiling"));
        assertThat(d.getExplanation().getSelectedTier()).isEqualTo("LOW");
    }

    @Test
    void candidateTableIsPopulatedAndSorted() {
        RoutingDecision d = engine().route(tenant("acme", "OPENAI,OLLAMA,ANTHROPIC", "HIGH"),
                ChatRequest.builder()
                        .messages(List.of(new ChatMessage("user", "hi")))
                        .build());
        List<RoutingExplanation.RankedProviderView> rows = d.getExplanation().getCandidates();
        assertThat(rows).isNotEmpty();
        for (RoutingExplanation.RankedProviderView row : rows) {
            assertThat(row.getProvider()).isNotEmpty();
            if (!row.isExcluded()) {
                assertThat(row.getFinalScore()).isFinite();
            }
        }
    }

    private RoutingEngine engine() {
        OrchestrixProperties props = new OrchestrixProperties();
        props.setProviders(Map.of(
                "openai", providerCfg(0.5, 1.5),
                "ollama", providerCfg(0.0, 0.0),
                "anthropic", providerCfg(3.0, 15.0)));
        props.setFallbackChain(List.of(ProviderType.OPENAI, ProviderType.ANTHROPIC, ProviderType.OLLAMA));

        ProviderRegistry registry = new ProviderRegistry(List.of(
                new TestLLMProvider(ProviderType.OPENAI, true, 0, "ok"),
                new TestLLMProvider(ProviderType.OLLAMA, true, 0, "ok"),
                new TestLLMProvider(ProviderType.ANTHROPIC, true, 0, "ok")));

        ProviderHealthTracker tracker = new ProviderHealthTracker(
                mock(ProviderHealthRepository.class),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()));
        tracker.init();

        TokenEstimatorRegistry tokens = Wiring.tokenRegistry();
        FeatureExtractor extractor = new FeatureExtractor(tokens);
        CostCalculator cost = new CostCalculator(props);
        ScoringService scoring = new ScoringService(props, tracker, cost);
        ProviderQualityHistory history = new ProviderQualityHistory();
        ProviderRanker ranker = new ProviderRanker(registry, tracker, tokens, history, cost, props);

        BudgetService budget = mock(BudgetService.class);
        when(budget.isOverBudget(any())).thenReturn(false);
        when(budget.isNearBudgetLimit(any())).thenReturn(false);

        return new RoutingEngine(extractor, scoring, registry, tracker, budget, props, ranker);
    }

    private OrchestrixProperties.Provider providerCfg(double in, double out) {
        OrchestrixProperties.Provider p = new OrchestrixProperties.Provider();
        p.setEnabled(true);
        p.setBaseUrl("http://localhost");
        p.setApiKey("test");
        p.setCostPer1kInputTokens(in);
        p.setCostPer1kOutputTokens(out);
        p.setModels(Map.of("low", "low", "mid", "mid", "high", "high"));
        return p;
    }

    private static Tenant tenant(String id, String allowed, String maxTier) {
        return Tenant.builder()
                .tenantId(id).name(id).apiKeyHash("h-" + id)
                .dailyBudgetUsd(new BigDecimal("100"))
                .monthlyBudgetUsd(new BigDecimal("3000"))
                .rateLimitRps(50).rateLimitRpm(500)
                .allowedProvidersCsv(allowed).maxModelTier(maxTier).enabled(true).build();
    }
}
