package com.orchestrix.quality;

import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderType;
import org.springframework.stereotype.Component;

/**
 * Cheap heuristic quality scorer. Looks at five surface signals:
 *
 *   1. Did the response end mid-sentence? (no terminal punctuation)
 *   2. Was the response empty or absurdly short for the prompt?
 *   3. Is there a balanced length ratio (completion vs. prompt)?
 *   4. Did the stream interrupt before the provider's done marker?
 *   5. Are there obvious truncation markers ("...", "—" at end)?
 *
 * These are intentionally not "is this answer correct?" — answering that
 * needs an LLM judge. The interface lets us add one later.
 */
@Component
public class HeuristicQualityEvaluator implements QualityEvaluator {

    @Override
    public QualitySignals evaluate(ProviderType provider, ProviderResponse response,
                                   int promptTokens, boolean streamInterrupted) {
        if (response == null || response.getContent() == null) {
            return QualitySignals.builder()
                    .score(0.0)
                    .malformed(true)
                    .completeness("low")
                    .streamInterrupted(streamInterrupted)
                    .lengthRatio(0.0)
                    .note("empty response")
                    .build();
        }

        String text = response.getContent().strip();
        int completionTokens = Math.max(1, response.getCompletionTokens());
        double ratio = promptTokens == 0 ? 1.0 : (double) completionTokens / Math.max(1, promptTokens);

        boolean malformed = isMalformed(text);
        String completeness = completenessBucket(text, completionTokens);

        // Start at 8 and apply penalties — keeps scores easy to read.
        double score = 8.0;
        StringBuilder note = new StringBuilder();

        if (text.isEmpty()) {
            score -= 8.0;
            note.append("empty;");
        }
        if (malformed) {
            score -= 2.5;
            note.append("malformed;");
        }
        if ("low".equals(completeness)) {
            score -= 2.0;
            note.append("short;");
        } else if ("high".equals(completeness)) {
            score += 1.0;
        }
        if (streamInterrupted) {
            score -= 2.0;
            note.append("stream-interrupted;");
        }
        if (ratio < 0.05) {
            score -= 1.0;
            note.append("ratio-too-low;");
        }
        if (note.length() == 0) note.append("clean;");

        // Mock responses are intentionally short — they read as "low completeness"
        // by the rules above. Annotate but don't penalize hard, so quality
        // history isn't poisoned by demo runs.
        if (text.startsWith("MOCK:")) {
            score = Math.max(score, 6.5);
            note.append("mock-response;");
        }

        return QualitySignals.builder()
                .score(Math.max(0.0, Math.min(10.0, score)))
                .malformed(malformed)
                .completeness(completeness)
                .streamInterrupted(streamInterrupted)
                .lengthRatio(ratio)
                .note(note.toString())
                .build();
    }

    private boolean isMalformed(String text) {
        if (text.isEmpty()) return true;
        char last = text.charAt(text.length() - 1);
        // Sentence terminators are reasonable signals of "complete output".
        boolean endsCleanly = last == '.' || last == '!' || last == '?'
                || last == ')' || last == ']' || last == '"' || last == '`';
        // Trailing ellipsis or em-dash usually marks truncation.
        boolean endsBadly = text.endsWith("...") || text.endsWith("—");
        return !endsCleanly || endsBadly;
    }

    private String completenessBucket(String text, int tokens) {
        if (tokens < 8) return "low";
        if (tokens < 60) return "medium";
        return "high";
    }
}
