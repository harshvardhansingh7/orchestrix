package com.orchestrix.mode;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ModelTier;
import com.orchestrix.domain.model.ProviderInvocation;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.domain.repository.ProviderHealthRepository;
import com.orchestrix.provider.ProviderHealthTracker;
import com.orchestrix.resilience.ResilientProviderInvoker;
import com.orchestrix.support.TestLLMProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that ProviderExecutor honors the active execution mode:
 *
 *   MOCK   → never calls the upstream provider.
 *   REAL   → calls upstream when configured; mocks if credentials missing.
 *   HYBRID → calls upstream; falls back to mock on any failure.
 */
class ProviderExecutorTest {

    private OrchestrixProperties props;
    private MockResponseFactory mockFactory;
    private ResilientProviderInvoker invoker;

    @BeforeEach
    void setUp() {
        props = new OrchestrixProperties();
        props.setProviders(Map.of(
                "openai", provider(0.5, 1.5),
                "ollama", provider(0.0, 0.0)));
        props.getResilience().setMaxRetries(1);
        props.getResilience().setRetryBaseBackoffMs(1);
        props.getResilience().setRequestTimeoutMs(2000);

        mockFactory = new MockResponseFactory();

        CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        ProviderHealthTracker tracker = new ProviderHealthTracker(mock(ProviderHealthRepository.class), cbReg);
        tracker.init();
        invoker = new ResilientProviderInvoker(cbReg, RetryRegistry.of(RetryConfig.ofDefaults()),
                tracker, props);
    }

    @Test
    void mockModeAlwaysReturnsMockEvenWhenProviderHealthy() {
        props.setMode("MOCK");
        ProviderExecutor exec = newExecutor();

        TestLLMProvider working = new TestLLMProvider(ProviderType.OPENAI, true, 0, "real-content");
        ExecutionResult r = exec.execute(working, invocation(ProviderType.OPENAI), ModelTier.MID).block();

        assertThat(r).isNotNull();
        assertThat(r.getExecutionType()).isEqualTo(ExecutionType.MOCK);
        assertThat(r.getResponse().getContent()).isEqualTo("MOCK: OpenAI response for MID tier");
        assertThat(working.callCount()).isEqualTo(0);
    }

    @Test
    void realModeWithoutCredentialsServesMockWithReason() {
        props.setMode("REAL");
        ProviderExecutor exec = newExecutor();

        // Configured=false provider — should never be called.
        TestLLMProvider notConfigured = new TestLLMProvider(ProviderType.OPENAI, true, 0, "real") {
            @Override public boolean isConfigured() { return false; }
        };

        ExecutionResult r = exec.execute(notConfigured, invocation(ProviderType.OPENAI), ModelTier.MID).block();

        assertThat(r).isNotNull();
        assertThat(r.getExecutionType()).isEqualTo(ExecutionType.MOCK);
        assertThat(r.getReason()).contains("credentials missing");
        assertThat(notConfigured.callCount()).isEqualTo(0);
    }

    @Test
    void realModeWithCredentialsCallsUpstream() {
        props.setMode("REAL");
        ProviderExecutor exec = newExecutor();

        TestLLMProvider working = new TestLLMProvider(ProviderType.OPENAI, true, 0, "real-from-openai");
        ExecutionResult r = exec.execute(working, invocation(ProviderType.OPENAI), ModelTier.MID).block();

        assertThat(r).isNotNull();
        assertThat(r.getExecutionType()).isEqualTo(ExecutionType.REAL);
        assertThat(r.getResponse().getContent()).isEqualTo("real-from-openai");
        assertThat(working.callCount()).isEqualTo(1);
    }

    @Test
    void hybridModeFallsBackToMockOnRealFailure() {
        props.setMode("HYBRID");
        ProviderExecutor exec = newExecutor();

        // Provider always fails — HYBRID should mask that with a mock.
        TestLLMProvider failing = new TestLLMProvider(ProviderType.OLLAMA, true, 99, "x");
        ExecutionResult r = exec.execute(failing, invocation(ProviderType.OLLAMA), ModelTier.LOW).block();

        assertThat(r).isNotNull();
        assertThat(r.getExecutionType()).isEqualTo(ExecutionType.MOCK);
        assertThat(r.getResponse().getContent()).isEqualTo("MOCK: Ollama response for LOW tier");
        assertThat(r.getReason()).contains("real call failed");
    }

    @Test
    void hybridModeReturnsRealWhenUpstreamSucceeds() {
        props.setMode("HYBRID");
        ProviderExecutor exec = newExecutor();

        TestLLMProvider working = new TestLLMProvider(ProviderType.OPENAI, true, 0, "real-content");
        ExecutionResult r = exec.execute(working, invocation(ProviderType.OPENAI), ModelTier.HIGH).block();

        assertThat(r.getExecutionType()).isEqualTo(ExecutionType.REAL);
        assertThat(r.getResponse().getContent()).isEqualTo("real-content");
    }

    @Test
    void mockResponseFactoryProducesTierAwareText() {
        assertThat(mockFactory.text(ProviderType.OPENAI, ModelTier.MID))
                .isEqualTo("MOCK: OpenAI response for MID tier");
        assertThat(mockFactory.text(ProviderType.OLLAMA, ModelTier.LOW))
                .isEqualTo("MOCK: Ollama response for LOW tier");
        assertThat(mockFactory.text(ProviderType.ANTHROPIC, ModelTier.HIGH))
                .isEqualTo("MOCK: Anthropic response for HIGH tier");
    }

    private ProviderExecutor newExecutor() {
        ModeService modeService = new ModeService(props);
        modeService.init();
        return new ProviderExecutor(modeService, mockFactory, invoker);
    }

    private static ProviderInvocation invocation(ProviderType type) {
        return ProviderInvocation.builder()
                .requestId("test-req")
                .tenantId("tenant-test")
                .provider(type)
                .model("test-model")
                .messages(List.of(new ChatMessage("user", "hi")))
                .build();
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
}
