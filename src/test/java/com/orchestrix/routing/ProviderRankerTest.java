package com.orchestrix.routing;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ModelTier;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.quality.ProviderQualityHistory;
import com.orchestrix.support.TestLLMProvider;
import com.orchestrix.support.Wiring;
import com.orchestrix.tokenizer.TokenEstimatorRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the weighted candidate scoring picks expected winners and
 * surfaces the candidate table for explanation.
 */
class ProviderRankerTest {

    @Test
    void freeProviderBeatsExpensiveOneAtSameHealth() {
        Ranking ctx = ranker();
        ProviderRanker.Ranking result = ctx.ranker.rank(
                List.of(ProviderType.OPENAI, ProviderType.OLLAMA), ModelTier.LOW,
                List.of(new ChatMessage("user", "hi")));

        assertThat(result.chosen()).isNotNull();
        assertThat(result.chosen().getProvider()).isEqualTo(ProviderType.OLLAMA);
        // Both providers should appear in the candidate table.
        assertThat(result.candidates()).hasSize(2);
    }

    @Test
    void unhealthyProviderRanksBelowHealthyAtSameCost() {
        Ranking ctx = ranker();
        // Hammer OPENAI with failures; Ollama stays clean.
        for (int i = 0; i < 30; i++) ctx.tracker.recordFailure(ProviderType.OPENAI, 5000);
        for (int i = 0; i < 30; i++) ctx.tracker.recordSuccess(ProviderType.OLLAMA, 100);

        ProviderRanker.Ranking result = ctx.ranker.rank(
                List.of(ProviderType.OPENAI, ProviderType.OLLAMA), ModelTier.MID,
                List.of(new ChatMessage("user", "hi")));

        assertThat(result.chosen().getProvider()).isEqualTo(ProviderType.OLLAMA);
    }

    @Test
    void qualityHistoryReducesCandidateScore() {
        Ranking ctx = ranker();
        // Baseline ranking with default quality history.
        ProviderRanker.Ranking before = ctx.ranker.rank(
                List.of(ProviderType.OPENAI, ProviderType.ANTHROPIC), ModelTier.HIGH,
                List.of(new ChatMessage("user", "Hello")));
        double openaiBefore = before.candidates().stream()
                .filter(c -> c.getProvider() == ProviderType.OPENAI)
                .findFirst().orElseThrow().getFinalWeightedScore();

        // Push OPENAI quality way down via history.
        for (int i = 0; i < 50; i++) ctx.history.record(ProviderType.OPENAI, 1.0);

        ProviderRanker.Ranking after = ctx.ranker.rank(
                List.of(ProviderType.OPENAI, ProviderType.ANTHROPIC), ModelTier.HIGH,
                List.of(new ChatMessage("user", "Hello")));
        double openaiAfter = after.candidates().stream()
                .filter(c -> c.getProvider() == ProviderType.OPENAI)
                .findFirst().orElseThrow().getFinalWeightedScore();

        // Quality history should subtract from the candidate's final score
        // (whether it flips the winner depends on cost gap — covered separately).
        assertThat(openaiAfter).isLessThan(openaiBefore);
    }

    @Test
    void candidateTableExposesSubScores() {
        Ranking ctx = ranker();
        ProviderRanker.Ranking result = ctx.ranker.rank(
                List.of(ProviderType.OPENAI, ProviderType.OLLAMA), ModelTier.MID,
                List.of(new ChatMessage("user", "hi")));

        for (ProviderCandidate c : result.candidates()) {
            assertThat(c.getCostScore()).isBetween(0.0, 10.0);
            assertThat(c.getLatencyScore()).isBetween(0.0, 10.0);
            assertThat(c.getHealthScore()).isBetween(0.0, 10.0);
            assertThat(c.getQualityScore()).isBetween(0.0, 10.0);
        }
    }

    private Ranking ranker() {
        OrchestrixProperties props = new OrchestrixProperties();
        props.setProviders(Map.of(
                "openai", providerCfg(0.5, 1.5),
                "ollama", providerCfg(0.0, 0.0),
                "anthropic", providerCfg(3.0, 15.0)));
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new TestLLMProvider(ProviderType.OPENAI, true, 0, "real"),
                new TestLLMProvider(ProviderType.OLLAMA, true, 0, "real"),
                new TestLLMProvider(ProviderType.ANTHROPIC, true, 0, "real")));
        ProviderHealthTracker tracker = new ProviderHealthTracker(
                mock(ProviderHealthRepository.class),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults()));
        tracker.init();
        TokenEstimatorRegistry tokens = Wiring.tokenRegistry();
        ProviderQualityHistory history = new ProviderQualityHistory();
        CostCalculator cost = new CostCalculator(props);
        ProviderRanker r = new ProviderRanker(registry, tracker, tokens, history, cost, props);
        return new Ranking(r, tracker, history);
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

    private record Ranking(ProviderRanker ranker, ProviderHealthTracker tracker,
                            ProviderQualityHistory history) {}
}
