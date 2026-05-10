package com.orchestrix.provider;

import com.orchestrix.domain.model.ProviderType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ProviderRegistry {

    private final Map<ProviderType, LLMProvider> byType = new EnumMap<>(ProviderType.class);

    public ProviderRegistry(List<LLMProvider> providers) {
        for (LLMProvider p : providers) {
            byType.put(p.type(), p);
        }
    }

    public Optional<LLMProvider> get(ProviderType type) {
        return Optional.ofNullable(byType.get(type));
    }

    public Collection<LLMProvider> all() {
        return byType.values();
    }

    public boolean isEnabled(ProviderType type) {
        return get(type).map(LLMProvider::enabled).orElse(false);
    }
}
