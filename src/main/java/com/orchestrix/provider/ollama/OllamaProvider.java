package com.orchestrix.provider.ollama;

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
public class OllamaProvider extends AbstractLLMProvider {

    private final WebClient webClient;
    private final ObjectMapper mapper;
    private final OrchestrixProperties props;

    public OllamaProvider(OrchestrixProperties props,
                          @Qualifier("providerWebClientBuilder") WebClient.Builder builder,
                          ObjectMapper mapper,
                          FailureInjectionRegistry failureInjection) {
        super(props.getProviders().getOrDefault("ollama", new OrchestrixProperties.Provider()), failureInjection);
        this.props = props;
        this.mapper = mapper;
        // Strip trailing slashes so combining with "/api/chat" never produces
        // a double-slash URL — that's a common cause of 404s against Ollama.
        String base = config.getBaseUrl() == null ? "http://localhost:11434" : config.getBaseUrl();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.webClient = builder
                .baseUrl(base)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ProviderType type() {
        return ProviderType.OLLAMA;
    }

    @Override
    public boolean isConfigured() {
        // Ollama has no API key; "configured" just means the adapter is on
        // and a base URL is set. Reachability is verified at call time —
        // HYBRID mode catches that and falls back to mock.
        if (!enabled()) return false;
        String url = config.getBaseUrl();
        return url != null && !url.isBlank();
    }

    @Override
    public Mono<ProviderResponse> generate(ProviderInvocation inv) {
        long delay = applyFailureInjection();
        long start = System.currentTimeMillis();
        Map<String, Object> body = buildBody(inv, false);

        Mono<JsonNode> call = webClient.post()
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(props.getResilience().getRequestTimeoutMs()))
                .onErrorMap(this::mapError);

        Mono<JsonNode> withDelay = delay > 0 ? Mono.delay(Duration.ofMillis(delay)).then(call) : call;

        return withDelay.map(json -> {
            String content = json.path("message").path("content").asText("");
            int promptTokens = json.path("prompt_eval_count").asInt(estimateTokens(inv.getMessages()));
            int completionTokens = json.path("eval_count").asInt(estimateTokens(content));
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
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .onErrorMap(this::mapError);

        Flux<String> withDelay = delay > 0 ? Mono.delay(Duration.ofMillis(delay)).thenMany(events) : events;

        int[] completionTokenCounter = {0};
        int promptTokens = estimateTokens(inv.getMessages());

        return withDelay.flatMap(line -> {
            if (line == null || line.isBlank()) return Mono.empty();
            try {
                JsonNode json = mapper.readTree(line);
                String delta = json.path("message").path("content").asText("");
                boolean done = json.path("done").asBoolean(false);
                if (!delta.isEmpty()) {
                    completionTokenCounter[0] += Math.max(1, delta.length() / 4);
                }
                return Mono.just(ProviderStreamChunk.builder()
                        .delta(delta)
                        .done(done)
                        .promptTokens(promptTokens)
                        .completionTokens(done
                                ? json.path("eval_count").asInt(completionTokenCounter[0])
                                : completionTokenCounter[0])
                        .finishReason(done ? json.path("done_reason").asText("stop") : null)
                        .build());
            } catch (Exception e) {
                log.debug("ollama stream parse skip: {}", e.getMessage());
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
        body.put("stream", stream);
        if (inv.getTemperature() != null || inv.getMaxTokens() != null) {
            Map<String, Object> options = new LinkedHashMap<>();
            if (inv.getTemperature() != null) options.put("temperature", inv.getTemperature());
            if (inv.getMaxTokens() != null) options.put("num_predict", inv.getMaxTokens());
            body.put("options", options);
        }
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
            boolean retryable = wcre.getStatusCode().is5xxServerError();
            return new ProviderException(type(),
                    "ollama " + wcre.getStatusCode().value() + ": " + wcre.getStatusText(),
                    retryable, t);
        }
        return new ProviderException(type(), t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(),
                true, t);
    }
}
