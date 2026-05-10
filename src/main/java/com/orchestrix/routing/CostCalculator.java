package com.orchestrix.routing;

import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.model.ProviderType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@RequiredArgsConstructor
public class CostCalculator {

    private final OrchestrixProperties props;

    public OrchestrixProperties.Provider config(ProviderType provider) {
        return props.getProviders().getOrDefault(provider.name().toLowerCase(),
                new OrchestrixProperties.Provider());
    }

    public double estimatedCostUsd(ProviderType provider, int promptTokens, int completionTokens) {
        OrchestrixProperties.Provider cfg = config(provider);
        double inUsd = (promptTokens / 1000.0) * cfg.getCostPer1kInputTokens();
        double outUsd = (completionTokens / 1000.0) * cfg.getCostPer1kOutputTokens();
        return inUsd + outUsd;
    }

    public BigDecimal exactCostUsd(ProviderType provider, int promptTokens, int completionTokens) {
        return BigDecimal.valueOf(estimatedCostUsd(provider, promptTokens, completionTokens))
                .setScale(6, RoundingMode.HALF_UP);
    }
}
