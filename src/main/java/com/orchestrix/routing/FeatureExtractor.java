package com.orchestrix.routing;

import com.orchestrix.domain.model.ChatMessage;
import com.orchestrix.domain.model.PromptFeatures;
import com.orchestrix.tokenizer.TokenEstimatorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Extracts structural signals from a chat request that the scoring service
 * combines into a complexity score. These are intentionally cheap heuristics —
 * a production rollout would also consider per-tenant historical model fit and
 * a small classifier for intent detection.
 */
@Component
public class FeatureExtractor {

    /** Optional — if available, uses provider-aware tokenizer for estimates. */
    private final TokenEstimatorRegistry tokenRegistry;

    @Autowired(required = false)
    public FeatureExtractor(TokenEstimatorRegistry tokenRegistry) {
        this.tokenRegistry = tokenRegistry;
    }

    /** Backward-compatible no-arg constructor used by older tests. */
    public FeatureExtractor() {
        this.tokenRegistry = null;
    }

    private static final Pattern CODE_FENCE = Pattern.compile("```|^\\s{4}\\S|\\bdef \\b|\\bclass \\b|\\bfunction \\b|=>",
            Pattern.MULTILINE);
    private static final Pattern ARCH_KEYWORDS = Pattern.compile(
            "\\b(architecture|design system|microservice|distributed|scalability|throughput|"
                    + "high availability|cap theorem|sharding|consistency|kafka|event[- ]driven|"
                    + "load balancer|cqrs|saga)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBUG_KEYWORDS = Pattern.compile(
            "\\b(debug|stack trace|exception|nullpointer|segfault|why (does|is|am)|fix|broken|"
                    + "not working|error[: ]|throws)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Provider-aware token estimate when a TokenEstimatorRegistry is wired,
     * otherwise the legacy {@code chars/4} heuristic. The neutral estimate is
     * computed using the OpenAI tokenizer so the complexity score is stable
     * across requests regardless of which provider ends up serving them.
     * Real billing always uses provider-reported usage.
     */
    public PromptFeatures extract(List<ChatMessage> messages) {
        StringBuilder all = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m.getContent() != null) {
                all.append(m.getContent()).append('\n');
            }
        }
        String text = all.toString();
        int chars = text.length();
        int estTokens = tokenRegistry != null
                ? Math.max(1, tokenRegistry.neutralEstimate(messages))
                : Math.max(1, chars / 4);

        boolean hasCode = CODE_FENCE.matcher(text).find();
        boolean isArch = ARCH_KEYWORDS.matcher(text).find();
        boolean isDebug = DEBUG_KEYWORDS.matcher(text).find();

        // length factor approaches 1 as prompt grows past ~2000 chars.
        double lengthFactor = Math.min(1.0, chars / 2000.0);

        return PromptFeatures.builder()
                .estimatedTokens(estTokens)
                .promptCharacters(chars)
                .hasCode(hasCode)
                .isArchitectureQuery(isArch)
                .isDebuggingQuery(isDebug)
                .lengthFactor(lengthFactor)
                .build();
    }
}
