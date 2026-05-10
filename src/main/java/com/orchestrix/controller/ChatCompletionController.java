package com.orchestrix.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.model.ChatRequest;
import com.orchestrix.domain.model.SimpleChatResponse;
import com.orchestrix.security.TenantContext;
import com.orchestrix.service.ChatCompletionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ChatCompletionService completionService;
    private final ObjectMapper objectMapper;

    /**
     * Unified chat endpoint.
     * <ul>
     *   <li>Non-streaming returns the demo-friendly {@link SimpleChatResponse}
     *       — six headline fields plus optional debug fields when relevant.</li>
     *   <li>Streaming (when {@code stream:true}) returns an SSE flow of
     *       token chunks; the final summary lands in logs and the persisted
     *       RequestLog row.</li>
     * </ul>
     */
    @PostMapping(value = "/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chat(@Valid @RequestBody ChatRequest request) {
        Tenant tenant = TenantContext.require();
        if (request.isStream()) {
            return stream(tenant, request);
        }
        return completionService.complete(tenant, request)
                .map(SimpleChatResponse::from)
                .map(ResponseEntity::ok);
    }

    private Flux<ServerSentEvent<String>> stream(Tenant tenant, ChatRequest request) {
        Flux<ServerSentEvent<String>> tokens = completionService.stream(tenant, request)
                .map(chunk -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("delta", chunk.getDelta());
                    body.put("done", chunk.isDone());
                    if (chunk.getFinishReason() != null) body.put("finishReason", chunk.getFinishReason());
                    if (chunk.getPromptTokens() != null) body.put("promptTokens", chunk.getPromptTokens());
                    if (chunk.getCompletionTokens() != null) body.put("completionTokens", chunk.getCompletionTokens());
                    return ServerSentEvent.<String>builder()
                            .event("token")
                            .data(toJson(body))
                            .build();
                });

        // Heartbeat every 15s so proxies don't drop the connection during long completions.
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<String>builder()
                        .comment("keep-alive")
                        .build());

        return tokens
                .mergeWith(heartbeat.takeUntilOther(tokens.then()))
                .onErrorResume(err -> Flux.just(ServerSentEvent.<String>builder()
                        .event("error")
                        .data(toJson(Map.of("type", err.getClass().getSimpleName(),
                                "message", err.getMessage() == null ? "error" : err.getMessage())))
                        .build()));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
