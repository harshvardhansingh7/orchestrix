package com.orchestrix.mode;

import com.orchestrix.domain.model.ProviderResponse;
import com.orchestrix.domain.model.ProviderType;
import lombok.Builder;
import lombok.Value;

/**
 * Outcome of a single provider attempt as seen by the orchestrator.
 *
 * Carries both the provider response and the metadata needed for honest
 * logging and the new simplified API response shape:
 * <ul>
 *   <li>{@link #executionType} — whether this was served by a real upstream
 *       call or a mock.</li>
 *   <li>{@link #reason} — when the result is a mock, why (mode=MOCK,
 *       credentials missing, real call failed, etc.). Null when REAL succeeded.</li>
 * </ul>
 */
@Value
@Builder
public class ExecutionResult {
    ProviderType provider;
    String model;
    ProviderResponse response;
    ExecutionType executionType;
    String reason;
}
