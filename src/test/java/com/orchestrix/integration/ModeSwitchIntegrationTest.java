package com.orchestrix.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.OrchestrixApplication;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.support.TestLLMProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box test of /admin/mode/switch + /v1/chat/completions interaction.
 * Confirms that flipping the mode at runtime changes how subsequent requests
 * are served (real vs mock), without restarting the app.
 */
@SpringBootTest(classes = OrchestrixApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ModeSwitchIntegrationTest.TestProviders.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModeSwitchIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    private final RestTemplate http = new RestTemplate();

    @TestConfiguration
    static class TestProviders {
        @Bean @Primary
        ProviderRegistry providerRegistry() {
            List<LLMProvider> ps = List.of(
                    new TestLLMProvider(ProviderType.OPENAI, true, 0, "REAL: openai answer"),
                    new TestLLMProvider(ProviderType.OLLAMA, true, 0, "REAL: ollama answer"),
                    new TestLLMProvider(ProviderType.ANTHROPIC, true, 0, "REAL: anthropic answer")
            );
            return new ProviderRegistry(ps);
        }
    }

    @Test @Order(1)
    void switchToMockMode() throws Exception {
        ResponseEntity<JsonNode> resp = http.exchange(url("/admin/mode/switch"), HttpMethod.POST,
                authJson("{\"mode\":\"MOCK\"}"), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().path("mode").asText()).isEqualTo("MOCK");
    }

    @Test @Order(2)
    void mockModeServesMockResponse() throws Exception {
        // Make sure we're in MOCK
        http.exchange(url("/admin/mode/switch"), HttpMethod.POST,
                authJson("{\"mode\":\"MOCK\"}"), JsonNode.class);

        ResponseEntity<JsonNode> resp = http.exchange(url("/v1/chat/completions"), HttpMethod.POST,
                authJson("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"), JsonNode.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().path("mode").asText()).isEqualTo("MOCK");
        assertThat(resp.getBody().path("response").asText()).startsWith("MOCK:");
        // Cost is 0 for mock responses.
        assertThat(resp.getBody().path("cost").asDouble()).isEqualTo(0.0);
    }

    @Test @Order(3)
    void switchBackToRealAndGetRealResponse() throws Exception {
        http.exchange(url("/admin/mode/switch"), HttpMethod.POST,
                authJson("{\"mode\":\"REAL\"}"), JsonNode.class);

        ResponseEntity<JsonNode> resp = http.exchange(url("/v1/chat/completions"), HttpMethod.POST,
                authJson("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"), JsonNode.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().path("mode").asText()).isEqualTo("REAL");
        assertThat(resp.getBody().path("response").asText()).startsWith("REAL:");
    }

    @Test @Order(4)
    void invalidModeReturns400() {
        RestTemplate noErr = new RestTemplate();
        noErr.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) {
                return false;
            }
        });
        ResponseEntity<String> resp = noErr.exchange(url("/admin/mode/switch"), HttpMethod.POST,
                authJsonString("{\"mode\":\"NOPE\"}"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test @Order(5)
    void providerStatusEndpointShowsConfigured() {
        ResponseEntity<JsonNode> resp = http.exchange(url("/admin/provider/status"), HttpMethod.GET,
                auth(), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().isArray()).isTrue();
        assertThat(resp.getBody().size()).isGreaterThanOrEqualTo(3);
        for (JsonNode row : resp.getBody()) {
            assertThat(row.path("provider").asText()).isIn("OPENAI", "OLLAMA", "ANTHROPIC");
            assertThat(row.has("configured")).isTrue();
            assertThat(row.has("enabled")).isTrue();
            assertThat(row.has("circuitState")).isTrue();
        }
    }

    @Test @Order(6)
    void tenantStateIncludesCurrentMode() {
        ResponseEntity<JsonNode> resp = http.exchange(url("/tenant/state"), HttpMethod.GET,
                auth(), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().has("currentExecutionMode")).isTrue();
    }

    private HttpEntity<String> authJson(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("orx_acme_demo_key_123");
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private HttpEntity<String> authJsonString(String body) {
        return authJson(body);
    }

    private HttpEntity<Void> auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("orx_acme_demo_key_123");
        return new HttpEntity<>(h);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
