package com.orchestrix.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.OrchestrixApplication;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.support.TestLLMProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OrchestrixApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ChatApiIntegrationTest.TestProviders.class)
class ChatApiIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    private final RestTemplate http = new RestTemplate();

    @TestConfiguration
    static class TestProviders {
        @Bean @Primary
        ProviderRegistry providerRegistry() {
            List<LLMProvider> ps = List.of(
                    new TestLLMProvider(ProviderType.OPENAI, true, 0, "hello-from-openai"),
                    new TestLLMProvider(ProviderType.OLLAMA, true, 0, "hello-from-ollama"),
                    new TestLLMProvider(ProviderType.ANTHROPIC, true, 0, "hello-from-anthropic")
            );
            return new ProviderRegistry(ps);
        }
    }

    @Test
    void rejectsMissingApiKey() throws Exception {
        // RestTemplate's default JDK client retries on 401 with auth challenge,
        // which fails here. java.net.http.HttpClient does not, so use it.
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url("/v1/chat/completions")))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        java.net.http.HttpResponse<String> resp = client.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void completesChatForValidTenant() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth("orx_acme_demo_key_123");
        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hello"))
        );
        ResponseEntity<String> resp = http.exchange(url("/v1/chat/completions"),
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body), h),
                String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = mapper.readTree(resp.getBody());
        assertThat(json.path("status").asText()).isEqualTo("completed");
        assertThat(json.path("provider").asText()).isIn("OPENAI", "OLLAMA", "ANTHROPIC");
        // SimpleChatResponse exposes the assistant text under "response".
        assertThat(json.path("response").asText()).startsWith("hello-from-");
        // New mode-aware fields are surfaced.
        assertThat(json.path("tier").asText()).isIn("LOW", "MID", "HIGH");
        assertThat(json.path("mode").asText()).isIn("MOCK", "REAL");
    }

    @Test
    void tenantMeReturnsBudgetInfo() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("orx_acme_demo_key_123");
        ResponseEntity<JsonNode> resp = http.exchange(url("/tenant/me"),
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().path("tenantId").asText()).isEqualTo("tenant-acme");
        assertThat(resp.getBody().path("dailyBudgetUsd").asDouble()).isPositive();
    }

    @Test
    void differentTenantsAreIsolated() {
        HttpHeaders acme = new HttpHeaders();
        acme.setBearerAuth("orx_acme_demo_key_123");
        ResponseEntity<JsonNode> a = http.exchange(url("/tenant/me"),
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(acme), JsonNode.class);

        HttpHeaders globex = new HttpHeaders();
        globex.setBearerAuth("orx_globex_demo_key_456");
        ResponseEntity<JsonNode> g = http.exchange(url("/tenant/me"),
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(globex), JsonNode.class);

        assertThat(a.getBody().path("tenantId").asText()).isEqualTo("tenant-acme");
        assertThat(g.getBody().path("tenantId").asText()).isEqualTo("tenant-globex");
        assertThat(a.getBody().path("tenantId").asText())
                .isNotEqualTo(g.getBody().path("tenantId").asText());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
