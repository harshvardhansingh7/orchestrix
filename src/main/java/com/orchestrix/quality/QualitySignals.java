package com.orchestrix.quality;

import lombok.Builder;
import lombok.Value;

/**
 * Per-response quality assessment. Stays intentionally small — the design
 * is for cheap heuristic signals plus a hook for plugging in an LLM-based
 * evaluator later.
 *
 * Score is on a 0..10 scale (10 = best) for legibility. Boolean flags
 * surface the specific concerns so logs and the explanation can quote them.
 */
@Value
@Builder
public class QualitySignals {

    /** 0..10. Higher is better. */
    double score;

    /** True if the response appears to end mid-sentence. */
    boolean malformed;

    /** "low" | "medium" | "high" — discrete completeness bucket. */
    String completeness;

    /** True if the stream was interrupted before the provider's "done" marker. */
    boolean streamInterrupted;

    /** Length ratio of completion to prompt. 1.0 = roughly equal. */
    double lengthRatio;

    /** Free-form note explaining why the score landed where it did. */
    String note;
}
