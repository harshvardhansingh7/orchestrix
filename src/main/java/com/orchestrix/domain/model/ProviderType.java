package com.orchestrix.domain.model;

public enum ProviderType {
    OPENAI,
    ANTHROPIC,
    OLLAMA;

    public static ProviderType from(String value) {
        if (value == null) return null;
        return ProviderType.valueOf(value.trim().toUpperCase());
    }
}
