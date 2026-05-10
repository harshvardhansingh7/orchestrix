package com.orchestrix.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import com.orchestrix.domain.repository.RequestLogRepository;
import com.orchestrix.mode.MockResponseFactory;
import com.orchestrix.mode.ModeService;
import com.orchestrix.mode.ProviderExecutor;
import com.orchestrix.observability.MetricsRecorder;
import com.orchestrix.observability.StructuredLogger;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.quality.HeuristicQualityEvaluator;
import com.orchestrix.quality.ProviderQualityHistory;
import com.orchestrix.resilience.ResilientProviderInvoker;
import com.orchestrix.routing.CostCalculator;
import com.orchestrix.routing.FeatureExtractor;
import com.orchestrix.routing.ProviderRanker;
import com.orchestrix.routing.RoutingEngine;
import com.orchestrix.routing.ScoringService;
import com.orchestrix.service.BudgetService;
import com.orchestrix.service.CacheService;
import com.orchestrix.service.ChatCompletionService;
import com.orchestrix.service.RateLimitService;
import com.orchestrix.tokenizer.AnthropicTokenEstimator;
import com.orchestrix.tokenizer.OllamaTokenEstimator;
import com.orchestrix.tokenizer.OpenAITokenEstimator;
import com.orchestrix.tokenizer.TokenEstimator;
import com.orchestrix.tokenizer.TokenEstimatorRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * Small builder that wires a fully-functional ChatCompletionService for tests.
 * Centralizes the boilerplate so every test doesn't break when constructor
 * signatures change.
 */
public final class Wiring {

    private Wiring() {}

    public static TokenEstimatorRegistry tokenRegistry() {
        return new TokenEstimatorRegistry(List.<TokenEstimator>of(
                new OpenAITokenEstimator(),
                new AnthropicTokenEstimator(),
                new OllamaTokenEstimator()));
    }

    public static ChatCompletionService completion(OrchestrixProperties props,
                                                    ProviderRegistry registry,
                                                    BudgetService budget,
                                                    CacheService cacheService) {
        CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        ProviderHealthTracker tracker = new ProviderHealthTracker(mock(ProviderHealthRepository.class), cbReg);
        tracker.init();
        ResilientProviderInvoker invoker = new ResilientProviderInvoker(cbReg,
                RetryRegistry.of(RetryConfig.ofDefaults()), tracker, props);

        TokenEstimatorRegistry tokens = tokenRegistry();
        FeatureExtractor extractor = new FeatureExtractor(tokens);
        CostCalculator cost = new CostCalculator(props);
        ScoringService scoring = new ScoringService(props, tracker, cost);
        ProviderQualityHistory qHistory = new ProviderQualityHistory();
        ProviderRanker ranker = new ProviderRanker(registry, tracker, tokens, qHistory, cost, props);

        RoutingEngine engine = new RoutingEngine(extractor, scoring, registry, tracker, budget, props, ranker);

        ModeService modeService = new ModeService(props);
        modeService.init();
        ProviderExecutor executor = new ProviderExecutor(modeService, new MockResponseFactory(), invoker);

        return new ChatCompletionService(
                engine, registry, executor, modeService, new RateLimitService(), budget,
                cacheService, cost, mock(RequestLogRepository.class),
                new StructuredLogger(), new MetricsRecorder(new SimpleMeterRegistry()),
                new HeuristicQualityEvaluator(), qHistory, new ObjectMapper());
    }
}
