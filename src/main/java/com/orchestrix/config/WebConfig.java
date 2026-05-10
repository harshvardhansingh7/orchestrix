package com.orchestrix.config;

import com.orchestrix.security.ApiKeyAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@RequiredArgsConstructor
public class WebConfig {

    /**
     * Register the API key filter so it runs before controller dispatch but
     * after Spring's request charset / forwarded-header filters.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/v1/*", "/admin/*", "/tenant/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }
}
