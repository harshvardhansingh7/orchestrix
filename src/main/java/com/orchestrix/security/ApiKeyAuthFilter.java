package com.orchestrix.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchestrix.domain.entity.Tenant;
import com.orchestrix.domain.repository.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates clients via the `Authorization: Bearer <api-key>` header.
 * Establishes the request id and tenant context for downstream layers.
 *
 * Public endpoints (actuator, swagger if added) are skipped via shouldNotFilter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_TENANT_ID = "tenantId";

    private final TenantRepository tenantRepository;
    private final ApiKeyHasher hasher;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.equals("/")
                || path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = Optional.ofNullable(request.getHeader(REQUEST_ID_HEADER))
                .filter(s -> !s.isBlank())
                .orElse(UUID.randomUUID().toString());
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            String apiKey = extractApiKey(request);
            if (apiKey == null) {
                writeError(response, HttpStatus.UNAUTHORIZED, "missing or malformed Authorization header");
                return;
            }

            String hash = hasher.hash(apiKey);
            Optional<Tenant> tenantOpt = tenantRepository.findByApiKeyHash(hash);
            if (tenantOpt.isEmpty()) {
                writeError(response, HttpStatus.UNAUTHORIZED, "invalid api key");
                return;
            }

            Tenant tenant = tenantOpt.get();
            if (!tenant.isEnabled()) {
                writeError(response, HttpStatus.FORBIDDEN, "tenant disabled");
                return;
            }

            TenantContext.set(tenant);
            MDC.put(MDC_TENANT_ID, tenant.getTenantId());
            log.info("[AUTH] requestId={} tenant={} authenticated", requestId, tenant.getTenantId());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT_ID);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private String extractApiKey(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        // Convenience header for curl-style usage during local dev.
        String alt = request.getHeader("X-Orchestrix-Api-Key");
        return (alt != null && !alt.isBlank()) ? alt.trim() : null;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
                "type", "AuthenticationException",
                "message", message,
                "requestId", MDC.get(MDC_REQUEST_ID)
        ));
    }
}
