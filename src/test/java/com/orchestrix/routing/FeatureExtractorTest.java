package com.orchestrix.routing;

import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.PromptFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureExtractorTest {

    private final FeatureExtractor extractor = new FeatureExtractor();

    @Test
    void detectsCodePresenceFromFencedBlocks() {
        PromptFeatures f = extractor.extract(List.of(new ChatMessage("user",
                "Why does this not work?\n```java\npublic class Foo {}\n```")));
        assertThat(f.isHasCode()).isTrue();
    }

    @Test
    void detectsArchitectureKeywords() {
        PromptFeatures f = extractor.extract(List.of(new ChatMessage("user",
                "Design a distributed event-driven architecture with sharding")));
        assertThat(f.isArchitectureQuery()).isTrue();
    }

    @Test
    void detectsDebuggingKeywords() {
        PromptFeatures f = extractor.extract(List.of(new ChatMessage("user",
                "I get a NullPointerException - why is this throwing?")));
        assertThat(f.isDebuggingQuery()).isTrue();
    }

    @Test
    void plainShortPromptHasNoSpecialFlags() {
        PromptFeatures f = extractor.extract(List.of(new ChatMessage("user", "hello")));
        assertThat(f.isHasCode()).isFalse();
        assertThat(f.isArchitectureQuery()).isFalse();
        assertThat(f.isDebuggingQuery()).isFalse();
        assertThat(f.getEstimatedTokens()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void lengthFactorSaturatesNearOne() {
        String big = "word ".repeat(800);
        PromptFeatures f = extractor.extract(List.of(new ChatMessage("user", big)));
        assertThat(f.getLengthFactor()).isEqualTo(1.0);
    }
}
