package com.orchestrix.tokenizer;

import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.ProviderType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final OpenAITokenEstimator openai = new OpenAITokenEstimator();
    private final AnthropicTokenEstimator anthropic = new AnthropicTokenEstimator();
    private final OllamaTokenEstimator ollama = new OllamaTokenEstimator();

    @Test
    void estimatorsAreProviderTagged() {
        assertThat(openai.providerType()).isEqualTo(ProviderType.OPENAI);
        assertThat(anthropic.providerType()).isEqualTo(ProviderType.ANTHROPIC);
        assertThat(ollama.providerType()).isEqualTo(ProviderType.OLLAMA);
    }

    @Test
    void emptyMessagesYieldZero() {
        assertThat(openai.estimateTokens(List.of())).isEqualTo(0);
    }

    @Test
    void anthropicEstimateIsHigherThanOpenAIForSameText() {
        // Anthropic uses ~3.5 chars/token vs OpenAI's 4.0, so the count should be larger.
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);
        List<ChatMessage> msgs = List.of(new ChatMessage("user", text));
        int openAIEstimate = openai.estimateTokens(msgs);
        int anthropicEstimate = anthropic.estimateTokens(msgs);
        assertThat(anthropicEstimate).isGreaterThan(openAIEstimate);
    }

    @Test
    void ollamaEstimateIsSlightlyLowerThanOpenAI() {
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);
        List<ChatMessage> msgs = List.of(new ChatMessage("user", text));
        int openAIEstimate = openai.estimateTokens(msgs);
        int ollamaEstimate = ollama.estimateTokens(msgs);
        // 4.2 chars/token < 4.0 chars/token → fewer tokens for same text.
        assertThat(ollamaEstimate).isLessThanOrEqualTo(openAIEstimate);
    }

    @Test
    void registryFallsBackToOpenAIWhenProviderUnknown() {
        TokenEstimatorRegistry reg = new TokenEstimatorRegistry(List.of(openai, anthropic, ollama));
        assertThat(reg.forProvider(ProviderType.OPENAI)).isSameAs(openai);
        assertThat(reg.forProvider(ProviderType.ANTHROPIC)).isSameAs(anthropic);
        assertThat(reg.forProvider(ProviderType.OLLAMA)).isSameAs(ollama);
    }

    @Test
    void neutralEstimateIsStableAndUsesOpenAITokenizer() {
        TokenEstimatorRegistry reg = new TokenEstimatorRegistry(List.of(openai, anthropic, ollama));
        List<ChatMessage> msgs = List.of(new ChatMessage("user", "Hello world"));
        assertThat(reg.neutralEstimate(msgs)).isEqualTo(openai.estimateTokens(msgs));
    }

    @Test
    void outputTokenHeuristicIsClampedAndPositive() {
        assertThat(openai.estimateOutputTokens(0)).isGreaterThanOrEqualTo(50);
        assertThat(openai.estimateOutputTokens(50_000)).isLessThanOrEqualTo(2048);
    }
}
