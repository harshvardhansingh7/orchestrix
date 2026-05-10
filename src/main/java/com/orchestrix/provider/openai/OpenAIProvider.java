package com.orchestrix.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.config.OrchestrixProperties;
import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ProviderInvocation;
import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderStreamChunk;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.exception.ProviderException;
import com.orchestrix.provider.AbstractLLMProvider;
import com.orchestrix.provider.FailureInjectionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAIProvider extends AbstractLLMProvider {

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final OrchestrixProperties props;

    public OpenAIProvider(OrchestrixProperties props,
                          @Qualifier("providerWebClientBuilder") WebClient.Builder builder,
                          ObjectMapper mapper,
                          FailureInjectionRegistry failureInjection) {
        super(props.getProviders().getOrDefault("openai", new OrchestrixProperties.Provider()), failureInjection);
        this.props = props;
        this.mapper = mapper;
        String base = config.getBaseUrl() == null ? "https://api.openai.com" : config.getBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = builder
                .baseUrl(base)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (config.getApiKey() == null ? "" : config.getApiKey()))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ProviderType type() {
        return ProviderType.OPENAI;
    }

    @Override
    public boolean isConfigured() {
        if (!enabled()) return false;
        String key = config.getApiKey();
        // Anything blank or the documented placeholder counts as "no key".
        return key != null && !key.isBlank() && !"sk-test-placeholder".equals(key);
    }

    @Override
    public Mono<ProviderResponse> generate(ProviderInvocation inv) {
        long delay = applyFailureInjection();
        long start = System.currentTimeMillis();
        Map<String, Object> body = buildBody(inv, false);

        Mono<JsonNode> call = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(props.getResilience().getRequestTimeoutMs()))
                .onErrorMap(this::mapError);

        Mono<JsonNode> withDelay = delay > 0 ? Mono.delay(Duration.ofMillis(delay)).then(call) : call;

        return withDelay.map(json -> {
            String content = json.path("choices").path(0).path("message").path("content").asText("");
            int promptTokens = json.path("usage").path("prompt_tokens").asInt(estimateTokens(inv.getMessages()));
            int completionTokens = json.path("usage").path("completion_tokens").asInt(estimateTokens(content));
            return ProviderResponse.builder()
                    .content(content)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .latencyMs(System.currentTimeMillis() - start)
                    .model(inv.getModel())
                    .provider(type())
                    .build();
        });
    }

    @Override
    public Flux<ProviderStreamChunk> stream(ProviderInvocation inv) {
        long delay = applyFailureInjection();
        Map<String, Object> body = buildBody(inv, true);

        Flux<String> events = webClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .onErrorMap(this::mapError);

        Flux<String> withDelay = delay > 0 ? Mono.delay(Duration.ofMillis(delay)).thenMany(events) : events;

        int[] completionTokenCounter = {0};
        int promptTokens = estimateTokens(inv.getMessages());

        return withDelay.flatMap(line -> {
            if (line == null || line.isBlank()) return Mono.empty();
            if ("[DONE]".equals(line.trim())) {
                return Mono.just(ProviderStreamChunk.builder()
                        .delta("")
                        .done(true)
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokenCounter[0])
                        .finishReason("stop")
                        .build());
            }
            try {
                JsonNode json = mapper.readTree(line);
                JsonNode choice = json.path("choices").path(0);
                String delta = choice.path("delta").path("content").asText("");
                String finish = choice.path("finish_reason").asText(null);
                if (!delta.isEmpty()) {
                    completionTokenCounter[0] += Math.max(1, delta.length() / 4);
                }
                return Mono.just(ProviderStreamChunk.builder()
                        .delta(delta)
                        .done(finish != null && !finish.isEmpty())
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokenCounter[0])
                        .finishReason(finish)
                        .build());
            } catch (Exception e) {
                log.debug("openai stream parse skip: {}", e.getMessage());
                return Mono.empty();
            }
        });
    }

    private Map<String, Object> buildBody(ProviderInvocation inv, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", inv.getModel());
        body.put("messages", inv.getMessages().stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .toList());
        if (inv.getMaxTokens() != null) body.put("max_tokens", inv.getMaxTokens());
        if (inv.getTemperature() != null) body.put("temperature", inv.getTemperature());
        if (stream) body.put("stream", true);
        return body;
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) total += m.getContent().length() / 4;
        return Math.max(1, total);
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    private Throwable mapError(Throwable t) {
        if (t instanceof WebClientResponseException wcre) {
            boolean retryable = wcre.getStatusCode().is5xxServerError() || wcre.getStatusCode().value() == 429;
            return new ProviderException(type(),
                    "openai " + wcre.getStatusCode().value() + ": " + wcre.getStatusText(),
                    retryable, t);
        }
        return new ProviderException(type(), t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(),
                true, t);
    }
}
