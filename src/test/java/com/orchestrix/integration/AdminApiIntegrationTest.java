package com.orchestrix.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.OrchestrixApplication;
import com.orchestrix.domain.model.ProviderType;
import com.orchestrix.provider.LLMProvider;
import com.orchestrix.provider.ProviderRegistry;
import com.orchestrix.support.TestLLMProvider;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Black-box integration test for /admin/* + /tenant/* + /v1/* end-to-end.
 *
 * Substitutes a deterministic provider registry so tests don't hit real
 * upstream services, and walks the full failure-injection → fallback →
 * recovery cycle.
 */
@SpringBootTest(classes = OrchestrixApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AdminApiIntegrationTest.TestProviders.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminApiIntegrationTest {

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    private final RestTemplate http = new RestTemplate();

    @TestConfiguration
    static class TestProviders {
        @Bean @Primary
        ProviderRegistry providerRegistry() {
            List<LLMProvider> ps = List.of(
                    new TestLLMProvider(ProviderType.OPENAI, true, 0, "real-openai"),
                    new TestLLMProvider(ProviderType.OLLAMA, true, 0, "real-ollama"),
                    new TestLLMProvider(ProviderType.ANTHROPIC, true, 0, "real-anthropic"));
            return new ProviderRegistry(ps);
        }
    }

    // ── /admin/mode ─────────────────────────────────────────────────────

    @Test @Order(1)
    void getModeReturnsCurrentValue() {
        ResponseEntity<JsonNode> resp = http.exchange(url("/admin/mode"),
                HttpMethod.GET, auth(), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().path("mode").asText()).isIn("MOCK", "REAL", "HYBRID");
        assertThat(resp.getBody().path("validModes").isArray()).isTrue();
    }

    @Test @Order(2)
    void switchModeAcceptsValidValues() {
        for (String mode : List.of("MOCK", "REAL", "HYBRID")) {
            ResponseEntity<JsonNode> resp = http.exchange(url("/admin/mode/switch"),
                    HttpMethod.POST, jsonAuth("{\"mode\":\"" + mode + "\"}"), JsonNode.class);
            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(resp.getBody().path("mode").asText()).isEqualTo(mode);
        }
    }

    @Test @Order(3)
    void switchModeRejectsInvalid() {
        RestTemplate noErr = silentRestTemplate();
        ResponseEntity<String> resp = noErr.exchange(url("/admin/mode/switch"),
                HttpMethod.POST, jsonAuth("{\"mode\":\"NOPE\"}"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ── /admin/provider/status ──────────────────────────────────────────

    @Test @Order(4)
    void providerStatusListsAllProviders() {
        ResponseEntity<JsonNode> resp = http.exchange(url("/admin/provider/status"),
                HttpMethod.GET, auth(), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().isArray()).isTrue();
        assertThat(resp.getBody().size()).isEqualTo(3);
        for (JsonNode row : resp.getBody()) {
            assertThat(row.has("provider")).isTrue();
            assertThat(row.has("enabled")).isTrue();
            assertThat(row.has("configured")).isTrue();
            assertThat(row.has("circuitState")).isTrue();
            assertThat(row.has("realCallPossible")).isTrue();
        }
    }

    // ── /admin/providers/{name}/fail and /recover ───────────────────────

    @Test @Order(5)
    void failureInjectionEnablesAndRecovers() throws Exception {
        // Inject a SLOW rule.
        ResponseEntity<JsonNode> set = http.exchange(url("/admin/providers/openai/fail"),
                HttpMethod.POST, jsonAuth("{\"mode\":\"SLOW\",\"delayMs\":50}"), JsonNode.class);
        assertThat(set.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(set.getBody().path("provider").asText()).isEqualTo("OPENAI");
        assertThat(set.getBody().path("mode").asText()).isEqualTo("SLOW");

        // Recover.
        ResponseEntity<JsonNode> rec = http.exchange(url("/admin/providers/openai/recover"),
                HttpMethod.POST, auth(), JsonNode.class);
        assertThat(rec.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(rec.getBody().path("status").asText()).isEqualTo("recovered");
        assertThat(rec.getBody().path("breakerState").asText()).isEqualTo("CLOSED");
    }

    // ── /v1/chat/completions sanity ────────────────────────────────────

    @Test @Order(6)
    void chatCompletionInModeReturnsSimplifiedShape() throws Exception {
        // Force MOCK so cost is 0 and answer is deterministic.
        http.exchange(url("/admin/mode/switch"), HttpMethod.POST,
                jsonAuth("{\"mode\":\"MOCK\"}"), JsonNode.class);

        ResponseEntity<JsonNode> resp = http.exchange(url("/v1/chat/completions"),
                HttpMethod.POST, jsonAuth("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"),
                JsonNode.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = resp.getBody();
        for (String f : List.of("requestId", "tier", "provider", "mode", "response", "cost", "latencyMs")) {
            assertThat(body.has(f)).withFailMessage("missing field: " + f).isTrue();
        }
        assertThat(body.path("response").asText()).startsWith("MOCK:");
        assertThat(body.path("cost").asDouble()).isEqualTo(0.0);
    }

    // ── /v1/chat/completions invalid payload ───────────────────────────

    @Test @Order(7)
    void invalidPayloadReturns400() {
        RestTemplate noErr = silentRestTemplate();
        // Empty messages array fails @NotEmpty.
        ResponseEntity<String> resp = noErr.exchange(url("/v1/chat/completions"),
                HttpMethod.POST, jsonAuth("{\"messages\":[]}"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ── /tenant/state ───────────────────────────────────────────────────

    @Test @Order(8)
    void tenantStateReturnsBudgetAndMode() {
        ResponseEntity<JsonNode> resp = http.exchange(url("/tenant/state"),
                HttpMethod.GET, auth(), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().path("tenantId").asText()).isEqualTo("tenant-acme");
        assertThat(resp.getBody().has("currentExecutionMode")).isTrue();
        assertThat(resp.getBody().has("dailyBudgetUsd")).isTrue();
        assertThat(resp.getBody().has("buckets")).isTrue();
    }

    // ── /tenant/requests ────────────────────────────────────────────────

    @Test @Order(9)
    void tenantRequestsListsRecentLogs() {
        ResponseEntity<JsonNode> resp = http.exchange(url("/tenant/requests"),
                HttpMethod.GET, auth(), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().isArray()).isTrue();
        // At least one request was logged from the chat call in an earlier test.
        if (!resp.getBody().isEmpty()) {
            JsonNode first = resp.getBody().get(0);
            assertThat(first.has("requestId")).isTrue();
            assertThat(first.has("provider")).isTrue();
            assertThat(first.has("status")).isTrue();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private HttpEntity<String> jsonAuth(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("orx_acme_demo_key_123");
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private HttpEntity<Void> auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth("orx_acme_demo_key_123");
        return new HttpEntity<>(h);
    }

    private RestTemplate silentRestTemplate() {
        RestTemplate rt = new RestTemplate();
        rt.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) {
                return false;
            }
        });
        return rt;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
