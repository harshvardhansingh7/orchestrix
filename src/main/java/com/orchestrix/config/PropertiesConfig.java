package com.orchestrix.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrchestrixProperties.class)
public class PropertiesConfig {
}
