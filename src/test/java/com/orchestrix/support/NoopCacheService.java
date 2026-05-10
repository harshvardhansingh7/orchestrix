package com.orchestrix.support;

import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.ChatResponse;
import com.orchestrix.service.CacheService;

import java.util.Optional;

/**
 * Test double for CacheService — always misses, never writes.
 * We extend the concrete class with null collaborators so callers don't need
 * to wire Redis or Jackson; the overrides ensure those collaborators are unused.
 */
public class NoopCacheService extends CacheService {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public NoopCacheService() {
        super(null, ((org.springframework.beans.factory.ObjectProvider) new NullObjectProvider<>()), null);
    }

    @Override
    public Optional<ChatResponse> get(Tenant tenant, ChatRequest request, String tier) {
        return Optional.empty();
    }

    @Override
    public void put(Tenant tenant, ChatRequest request, String tier, ChatResponse response) {
        // no-op
    }
}
