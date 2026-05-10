package com.orchestrix.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cache for non-streaming completions, keyed on tenantId + hash(prompt + model-tier).
 *
 *   - Streaming responses are never cached.
 *   - Cache is opt-in via orchestrix.cache.enabled.
 *   - Falls back to a bounded in-memory LRU when Redis is disabled or unreachable
 *     so the demo works without external infra.
 *
 * Concurrency: Redis SETEX is atomic. The in-memory map uses a synchronized
 * LinkedHashMap so two parallel requests for the same key cannot corrupt state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final OrchestrixProperties props;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    private final Map<String, CacheEntry> inMemory = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > 5000;
        }
    };

    public Optional<ChatResponse> get(Tenant tenant, ChatRequest request, String tier) {
        if (!props.getCache().isEnabled() || request.isStream()) return Optional.empty();
        String key = key(tenant, request, tier);
        try {
            ChatResponse fromRedis = readRedis(key);
            if (fromRedis != null) return Optional.of(fromRedis);
        } catch (Exception e) {
            log.debug("redis cache read failed; falling back to memory: {}", e.getMessage());
        }
        return readMemory(key);
    }

    public void put(Tenant tenant, ChatRequest request, String tier, ChatResponse response) {
        if (!props.getCache().isEnabled() || request.isStream()) return;
        if (response == null || response.getContent() == null) return;
        String key = key(tenant, request, tier);
        try {
            writeRedis(key, response);
        } catch (Exception e) {
            log.debug("redis cache write failed; using memory: {}", e.getMessage());
        }
        writeMemory(key, response);
    }

    private void writeRedis(String key, ChatResponse response) throws JsonProcessingException {
        if (!props.getCache().isRedisEnabled()) return;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return;
        String json = objectMapper.writeValueAsString(response);
        redis.opsForValue().set(key, json, Duration.ofSeconds(props.getCache().getTtlSeconds()));
    }

    private ChatResponse readRedis(String key) throws JsonProcessingException {
        if (!props.getCache().isRedisEnabled()) return null;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return null;
        String json = redis.opsForValue().get(key);
        return json == null ? null : objectMapper.readValue(json, ChatResponse.class);
    }

    private synchronized void writeMemory(String key, ChatResponse response) {
        inMemory.put(key, new CacheEntry(response, System.currentTimeMillis()
                + Duration.ofSeconds(props.getCache().getTtlSeconds()).toMillis()));
    }

    private synchronized Optional<ChatResponse> readMemory(String key) {
        CacheEntry e = inMemory.get(key);
        if (e == null) return Optional.empty();
        if (e.expiresAtMs < System.currentTimeMillis()) {
            inMemory.remove(key);
            return Optional.empty();
        }
        return Optional.of(e.response);
    }

    private String key(Tenant tenant, ChatRequest request, String tier) {
        StringBuilder sb = new StringBuilder();
        sb.append(tenant.getTenantId()).append("|").append(tier).append("|");
        for (var m : request.getMessages()) {
            sb.append(m.getRole()).append(':').append(m.getContent()).append('\n');
        }
        return "orchestrix:cache:" + sha256(sb.toString());
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record CacheEntry(ChatResponse response, long expiresAtMs) {}
}
