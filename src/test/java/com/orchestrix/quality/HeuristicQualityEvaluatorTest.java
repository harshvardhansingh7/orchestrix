package com.orchestrix.quality;

import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicQualityEvaluatorTest {

    private final HeuristicQualityEvaluator eval = new HeuristicQualityEvaluator();

    @Test
    void cleanLongResponseScoresHigh() {
        ProviderResponse r = response("This is a complete answer. It ends cleanly.", 80);
        QualitySignals q = eval.evaluate(ProviderType.OPENAI, r, 50, false);
        assertThat(q.getScore()).isGreaterThanOrEqualTo(8.0);
        assertThat(q.isMalformed()).isFalse();
        assertThat(q.getCompleteness()).isEqualTo("high");
    }

    @Test
    void truncatedResponseFlaggedAsMalformed() {
        ProviderResponse r = response("This is unfinis", 5);
        QualitySignals q = eval.evaluate(ProviderType.OPENAI, r, 50, false);
        assertThat(q.isMalformed()).isTrue();
        assertThat(q.getScore()).isLessThan(8.0);
    }

    @Test
    void emptyResponseGetsZero() {
        ProviderResponse r = response("", 0);
        QualitySignals q = eval.evaluate(ProviderType.OPENAI, r, 50, false);
        assertThat(q.getScore()).isEqualTo(0.0);
        assertThat(q.isMalformed()).isTrue();
    }

    @Test
    void streamInterruptionPenalizes() {
        ProviderResponse r = response("Some answer.", 30);
        QualitySignals clean = eval.evaluate(ProviderType.OPENAI, r, 50, false);
        QualitySignals interrupted = eval.evaluate(ProviderType.OPENAI, r, 50, true);
        assertThat(interrupted.getScore()).isLessThan(clean.getScore());
        assertThat(interrupted.isStreamInterrupted()).isTrue();
    }

    @Test
    void mockResponsesGetDecentBaselineScore() {
        ProviderResponse r = response("MOCK: OpenAI response for MID tier", 12);
        QualitySignals q = eval.evaluate(ProviderType.OPENAI, r, 30, false);
        assertThat(q.getScore()).isGreaterThanOrEqualTo(6.0);
        assertThat(q.getNote()).contains("mock-response");
    }

    @Test
    void historyDecaysTowardRecentObservations() {
        ProviderQualityHistory history = new ProviderQualityHistory();
        // Default is 8.0; pump in low scores and verify it drops over time.
        for (int i = 0; i < 50; i++) history.record(ProviderType.OLLAMA, 2.0);
        assertThat(history.average(ProviderType.OLLAMA)).isLessThan(4.0);
        // Then pump in high scores and verify it rises again.
        for (int i = 0; i < 50; i++) history.record(ProviderType.OLLAMA, 9.5);
        assertThat(history.average(ProviderType.OLLAMA)).isGreaterThan(7.0);
    }

    private ProviderResponse response(String content, int completionTokens) {
        return ProviderResponse.builder()
                .content(content)
                .promptTokens(50)
                .completionTokens(completionTokens)
                .latencyMs(10)
                .model("test")
                .provider(ProviderType.OPENAI)
                .build();
    }
}
