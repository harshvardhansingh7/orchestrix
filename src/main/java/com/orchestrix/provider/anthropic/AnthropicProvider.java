package com.orchestrix.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AnthropicProvider extends AbstractLLMProvider {

    private final WebClient webClient;
    private final OrchestrixProperties props;

    public AnthropicProvider(OrchestrixProperties props,
                             @Qualifier("providerWebClientBuilder") WebClient.Builder builder,
                             FailureInjectionRegistry failureInjection) {
        super(props.getProviders().getOrDefault("anthropic", new OrchestrixProperties.Provider()), failureInjection);
        this.props = props;
        String base = config.getBaseUrl() == null ? "https://api.anthropic.com" : config.getBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = builder
                .baseUrl(base)
                .defaultHeader("x-api-key", config.getApiKey() == null ? "" : config.getApiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ProviderType type() {
        return ProviderType.ANTHROPIC;
    }

    @Override
    public boolean isConfigured() {
        if (!enabled()) return false;
        String key = config.getApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public Mono<ProviderResponse> generate(ProviderInvocation inv) {
        long delay = applyFailureInjection();
        long start = System.currentTimeMillis();
        Map<String, Object> body = buildBody(inv, false);

        Mono<JsonNode> call = webClient.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(props.getResilience().getRequestTimeoutMs()))
                .onErrorMap(this::mapError);

        Mono<JsonNode> withDelay = delay > 0 ? Mono.delay(Duration.ofMillis(delay)).then(call) : call;

        return withDelay.map(json -> {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : json.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText(""));
                }
            }
            int promptTokens = json.path("usage").path("input_tokens").asInt(estimateTokens(inv.getMessages()));
            int completionTokens = json.path("usage").path("output_tokens").asInt(estimateTokens(sb.toString()));
            return ProviderResponse.builder()
                    .content(sb.toString())
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
        // Anthropic streams via SSE events; full implementation parses event types.
        // For demo cleanliness we fall through to non-streaming and emit one chunk.
        return generate(inv).flatMapMany(resp -> Flux.just(
                ProviderStreamChunk.builder()
                        .delta(resp.getContent())
                        .done(true)
                        .promptTokens(resp.getPromptTokens())
                        .completionTokens(resp.getCompletionTokens())
                        .finishReason("stop")
                        .build()
        ));
    }

    private Map<String, Object> buildBody(ProviderInvocation inv, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", inv.getModel());
        body.put("max_tokens", inv.getMaxTokens() != null ? inv.getMaxTokens() : 1024);
        if (inv.getTemperature() != null) body.put("temperature", inv.getTemperature());

        // Anthropic separates system prompts from messages.
        StringBuilder system = new StringBuilder();
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage m : inv.getMessages()) {
            if ("system".equalsIgnoreCase(m.getRole())) {
                if (system.length() > 0) system.append("\n");
                system.append(m.getContent());
            } else {
                messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
            }
        }
        if (system.length() > 0) body.put("system", system.toString());
        body.put("messages", messages);
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
                    "anthropic " + wcre.getStatusCode().value() + ": " + wcre.getStatusText(),
                    retryable, t);
        }
        return new ProviderException(type(), t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(),
                true, t);
    }
}
