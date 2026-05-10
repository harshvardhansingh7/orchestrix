package com.orchestrix.mode;

import com.orchestrix.domain.model.ModelTier;
import com.orchestrix.domain.model.ProviderInvocation;
import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.exception.ProviderException;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.resilience.ResilientProviderInvoker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Mode-aware dispatcher. For each provider attempt the orchestrator delegates
 * here, and the executor decides — based on {@link ModeService} and the
 * provider's {@code isConfigured()} — whether to issue the real upstream call
 * or to return a deterministic mock.
 *
 * Mode semantics:
 * <pre>
 *   MOCK   → always mock (no upstream).
 *   REAL   → if !isConfigured  → mock with reason "credentials missing".
 *            else              → call real (failures bubble to fallback chain).
 *   HYBRID → if !isConfigured  → mock with reason "credentials missing".
 *            else              → try real; on any failure → mock with reason.
 * </pre>
 *
 * The routing engine, fallback chain, retry policy, circuit breakers, and
 * rate limits are unchanged. Only the leaf "did this attempt succeed and
 * how" decision moves through here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderExecutor {

    private final ModeService modeService;
    private final MockResponseFactory mockFactory;
    private final ResilientProviderInvoker invoker;

    public Mono<ExecutionResult> execute(LLMProvider provider, ProviderInvocation inv, ModelTier tier) {
        ExecutionMode mode = modeService.current();

        if (mode == ExecutionMode.MOCK) {
            return Mono.just(mockResult(provider, tier, inv.getModel(), "mode=MOCK"));
        }

        if (!provider.isConfigured()) {
            return Mono.just(mockResult(provider, tier, inv.getModel(), "credentials missing"));
        }

        Mono<ExecutionResult> realCall = invoker.invoke(provider, inv)
                .map(pr -> realResult(provider, inv.getModel(), pr));

        if (mode == ExecutionMode.HYBRID) {
            return realCall.onErrorResume(err -> {
                log.warn("hybrid-fallback-to-mock provider={} reason={}", provider.type(), explain(err));
                return Mono.just(mockResult(provider, tier, inv.getModel(),
                        "real call failed: " + explain(err)));
            });
        }

        // REAL mode with credentials present — failures continue to bubble through
        // the fallback chain owned by ChatCompletionService.
        return realCall;
    }

    public Flux<StreamExecution> executeStream(LLMProvider provider, ProviderInvocation inv, ModelTier tier) {
        ExecutionMode mode = modeService.current();

        if (mode == ExecutionMode.MOCK) {
            return mockFactory.stream(provider.type(), tier)
                    .map(chunk -> StreamExecution.of(chunk, ExecutionType.MOCK, "mode=MOCK"));
        }

        if (!provider.isConfigured()) {
            return mockFactory.stream(provider.type(), tier)
                    .map(chunk -> StreamExecution.of(chunk, ExecutionType.MOCK, "credentials missing"));
        }

        // HYBRID streaming: only switch to mock if NO real chunks have been
        // emitted yet. Splicing mock tokens onto a partial real stream would
        // corrupt the response — mid-stream failures must end the stream
        // (orchestrator records "incomplete_stream") rather than be patched.
        java.util.concurrent.atomic.AtomicInteger realChunks = new java.util.concurrent.atomic.AtomicInteger(0);
        Flux<StreamExecution> realStream = invoker.invokeStream(provider, inv)
                .doOnNext(c -> realChunks.incrementAndGet())
                .map(chunk -> StreamExecution.of(chunk, ExecutionType.REAL, null));

        if (mode == ExecutionMode.HYBRID) {
            return realStream.onErrorResume(err -> {
                if (realChunks.get() > 0) {
                    // Don't splice — propagate so orchestrator marks
                    // status=incomplete_stream and the client retries cleanly.
                    log.warn("hybrid-stream-mid-flight-failure provider={} emittedChunks={} reason={} (no mock splice)",
                            provider.type(), realChunks.get(), explain(err));
                    return Flux.error(err);
                }
                log.warn("hybrid-stream-fallback-to-mock provider={} reason={}",
                        provider.type(), explain(err));
                return mockFactory.stream(provider.type(), tier)
                        .map(chunk -> StreamExecution.of(chunk, ExecutionType.MOCK,
                                "real stream failed: " + explain(err)));
            });
        }
        return realStream;
    }

    private ExecutionResult mockResult(LLMProvider provider, ModelTier tier, String requestedModel, String reason) {
        String model = requestedModel != null ? requestedModel
                : provider.resolveModel(tier.name().toLowerCase());
        ProviderResponse mock = mockFactory.build(provider.type(), tier, model);
        return ExecutionResult.builder()
                .provider(provider.type())
                .model(model)
                .response(mock)
                .executionType(ExecutionType.MOCK)
                .reason(reason)
                .build();
    }

    private ExecutionResult realResult(LLMProvider provider, String model, ProviderResponse pr) {
        return ExecutionResult.builder()
                .provider(provider.type())
                .model(model)
                .response(pr)
                .executionType(ExecutionType.REAL)
                .reason(null)
                .build();
    }

    private String explain(Throwable t) {
        if (t instanceof ProviderException pe) {
            String msg = pe.getMessage() == null ? "error" : pe.getMessage();
            return pe.getProvider() + ":" + msg;
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** Pair of stream chunk + execution type for streaming flows. */
    public record StreamExecution(ProviderStreamChunk chunk, ExecutionType executionType, String reason) {
        static StreamExecution of(ProviderStreamChunk chunk, ExecutionType type, String reason) {
            return new StreamExecution(chunk, type, reason);
        }
    }
}
