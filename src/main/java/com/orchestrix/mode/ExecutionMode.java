package com.orchestrix.mode;

/**
 * The system-wide execution mode, controlling how each provider call is served.
 *
 * <ul>
 *   <li>{@link #MOCK} — Always return a deterministic mock. No upstream calls.
 *       Safe for demos, evaluation, and CI without API keys.</li>
 *   <li>{@link #REAL} — Use the real provider. If credentials are not
 *       configured for that provider, return a mock with an explicit
 *       "credentials missing" reason rather than failing the request.</li>
 *   <li>{@link #HYBRID} — Try the real provider first; on any failure
 *       (missing credentials, connection refused, timeout, error), serve a
 *       mock for the same provider with the failure reason recorded.</li>
 * </ul>
 *
 * Mode applies after the routing engine has chosen the tier and provider.
 * Routing decisions are unaffected by the mode — we only change how the
 * chosen provider's call is executed.
 */
public enum ExecutionMode {
    MOCK,
    REAL,
    HYBRID;

    public static ExecutionMode parse(String value, ExecutionMode fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return ExecutionMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
