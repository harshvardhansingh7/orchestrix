package com.orchestrix.domain.model;

public enum ModelTier {
    LOW,   // Cheap / local model (e.g., Ollama)
    MID,   // Mid-tier hosted (e.g., gpt-4o-mini)
    HIGH;  // Premium (e.g., gpt-4o, claude-3-5-sonnet)

    public boolean atMost(ModelTier other) {
        return this.ordinal() <= other.ordinal();
    }
}
