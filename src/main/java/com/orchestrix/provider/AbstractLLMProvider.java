package com.orchestrix.provider;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.exception.ProviderException;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public abstract class AbstractLLMProvider implements LLMProvider {

    protected final OrchestrixProperties.Provider config;
    protected final FailureInjectionRegistry failureInjection;

    protected AbstractLLMProvider(OrchestrixProperties.Provider config, FailureInjectionRegistry failureInjection) {
        this.config = config;
        this.failureInjection = failureInjection;
    }

    @Override
    public boolean enabled() {
        return config != null && config.isEnabled();
    }

    @Override
    public String resolveModel(String tier) {
        Map<String, String> models = config.getModels();
        if (tier == null) tier = "mid";
        String model = models.get(tier.toLowerCase());
        if (model == null) {
            model = models.getOrDefault("mid", models.values().stream().findFirst().orElse("default"));
        }
        return model;
    }

    /**
     * Apply failure injection. Returns the delay to sleep before the upstream call,
     * or throws ProviderException if the rule says to error out immediately.
     */
    protected long applyFailureInjection() {
        long delay = failureInjection.applyAndComputeDelay(type());
        if (delay < 0) {
            throw new ProviderException(type(), "failure-injection: simulated error", true);
        }
        if (delay > 0) {
            log.warn("failure-injection delaying provider={} delayMs={}", type(), delay);
        }
        return delay;
    }

    protected ProviderException toProviderException(Throwable t) {
        if (t instanceof ProviderException pe) return pe;
        boolean retryable = !(t instanceof IllegalArgumentException);
        return new ProviderException(type(), t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(),
                retryable, t);
    }

    public abstract ProviderType type();
}
