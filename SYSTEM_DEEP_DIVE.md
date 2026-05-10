# Orchestrix — System Deep Dive

This document is the "open the engine and look inside" companion to
[`README.md`](./README.md) and [`DESIGN.md`](./DESIGN.md).

It walks through:

1. Every module class-by-class — what it does and why it exists.
2. The full request lifecycle from HTTP byte to log row.
3. Sample API requests, responses, and log lines.
4. The routing formula in plain and technical form, with worked examples.

Read this when you are about to change Orchestrix and want to know what each
piece is on the hook for.

---

## 1. Class-by-class tour

### 1.1 Routing layer (`com.orchestrix.routing`)

| Class                | What it does                                                          |
|----------------------|------------------------------------------------------------------------|
| `FeatureExtractor`   | Reads chat messages and produces a `PromptFeatures` value: estimated tokens, char count, code/architecture/debugging flags, length factor. Pure regex + counting; no I/O. |
| `CostCalculator`     | Maps `(provider, prompt_tokens, completion_tokens)` → estimated USD. Looks up `costPer1kInputTokens` / `costPer1kOutputTokens` from `OrchestrixProperties`. |
| `ScoringService`     | Applies the documented complexity / cost / reliability formulas and projects them to a tier. Stateless apart from the injected health tracker for reliability. |
| `RoutingEngine`      | Top-level decision maker. Builds the eligible-provider list, scores once, applies overrides (tenant ceiling, budget, breaker), picks primary + fallback chain, returns a `RoutingDecision`. |

These four together convert "user request + tenant policy + provider health"
into "go call provider X with model Y, then try Z and W if X fails".

### 1.2 Provider layer (`com.orchestrix.provider`, `com.orchestrix.provider.*`)

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `LLMProvider`               | The adapter contract: `type()`, `enabled()`, `isConfigured()`, `resolveModel(tier)`, `generate(inv)`, `stream(inv)`. Reactive return types so the gateway is non-blocking. |
| `AbstractLLMProvider`       | Shared base: holds the per-provider config, applies failure injection, maps errors to `ProviderException`, default `resolveModel` reads from the model map. |
| `OpenAIProvider`            | Talks `/v1/chat/completions` over a Spring WebClient. Parses both the JSON body and the SSE stream format. `isConfigured()` checks the API key isn't blank or the placeholder. |
| `OllamaProvider`            | Talks `/api/chat` to a local Ollama server. Streams NDJSON. `isConfigured()` only requires a base URL (no API key). |
| `AnthropicProvider`         | Talks `/v1/messages`. Splits chat messages into Anthropic's `system` + `messages` shape. Streaming currently emits a single chunk derived from the non-streaming response. |
| `ProviderRegistry`          | `Map<ProviderType, LLMProvider>` lookup; built from all `LLMProvider` Spring beans at startup. The orchestrator and admin controllers go through this registry. |
| `ProviderHealthTracker`     | In-memory counters per provider (calls, failures, latency, consecutive failures, last success/failure). Computes `healthScore`, knows the circuit-breaker state, and flushes to MySQL every 30s. |
| `FailureInjectionRegistry`  | Test/demo hook. Admin can install a `Rule` (NONE/SLOW/TIMEOUT/ERROR/ALWAYS_ERROR) per provider. The adapter consults it on every call to inject latency or synthetic errors. |

### 1.3 Resilience layer (`com.orchestrix.resilience`)

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `Resilience4jConfig`        | Builds the per-provider `CircuitBreaker` and `Retry` registries with the documented thresholds (sliding window 20, failure rate 50%, retry 3 attempts with exponential backoff). |
| `ResilientProviderInvoker`  | Wraps a single real call with the circuit breaker, retry, and a hard timeout. Updates the health tracker on every outcome. Used by `ProviderExecutor` for REAL/HYBRID modes. |

### 1.4 Mode layer (`com.orchestrix.mode`)

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `ExecutionMode`             | `MOCK` / `REAL` / `HYBRID` enum + parser. The system-wide mode setting. |
| `ExecutionType`             | `MOCK` / `REAL` enum. The actual outcome of a single attempt — what served the response. |
| `ModeService`               | `AtomicReference<ExecutionMode>` holder. Reads the boot value from `OrchestrixProperties.mode`; flippable at runtime via `setMode`. |
| `MockResponseFactory`       | Produces deterministic tier/provider mock content (`"MOCK: OpenAI response for MID tier"`) plus a two-chunk mock stream. |
| `ProviderExecutor`          | Mode-aware dispatcher. Decides whether each attempt becomes a real call (via `ResilientProviderInvoker`) or a mock (via `MockResponseFactory`), based on `ModeService.current()` and `provider.isConfigured()`. Returns an `ExecutionResult`. |
| `ExecutionResult`           | Value type carrying `(provider, model, response, executionType, reason)`. Threaded through the orchestrator so logs and the client response can show whether a mock was served and why. |

### 1.5 Service layer (`com.orchestrix.service`)

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `ChatCompletionService`     | The orchestrator. Owns the request lifecycle: rate limit → budget → cache lookup → routing → executor → persist. Both non-streaming (`complete`) and streaming (`stream`) entry points. |
| `BudgetService`             | Reads/writes `tenant_usage`. Enforces hard daily caps (throws `BudgetExceededException` → 402) and exposes `isOverBudget` / `isNearBudgetLimit` for the routing engine to apply soft overrides. |
| `RateLimitService`          | In-memory dual token bucket per tenant (RPS + RPM). Throws `RateLimitExceededException` (429) when exhausted. |
| `CacheService`              | Tenant-scoped cache, keyed on `tenantId + tier + hash(messages)`. Backed by Redis when enabled; bounded in-memory LRU otherwise. Streams are never cached, and **only `executionType == REAL` results** are cached. |

### 1.6 Security (`com.orchestrix.security`)

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `ApiKeyHasher`              | SHA-256 of raw API key — what we store in the DB.                     |
| `ApiKeyAuthFilter`          | Servlet filter on `/v1/*`, `/admin/*`, `/tenant/*`. Hashes the bearer token, looks up the tenant, sets `TenantContext` and MDC (`requestId`, `tenantId`). Rejects with 401 on missing/invalid keys, 403 on disabled tenants. |
| `TenantContext`             | Thread-local holder for the authenticated tenant. Cleared in the filter's `finally` block. |

### 1.7 Persistence (`com.orchestrix.domain`)

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `Tenant`                    | Tenant identity, hashed key, daily/monthly budgets, rate limits, allowed providers, max model tier. |
| `RequestLog`                | One row per completed (success or terminal failure) request. Includes routing scores, tokens, cost, latency, status, fallback chain, and **mode + executionType + executionReason** for honest auditing under mode flips. |
| `ProviderHealthEntity`      | Last-flushed snapshot of a provider's health stats — survives restarts. |
| `TenantUsage`               | Daily-aggregated tenant spend used to enforce budgets. |
| Repositories                | Spring Data JPA interfaces for the four entities above. |

### 1.8 Observability (`com.orchestrix.observability`)

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `StructuredLogger`          | Emits both demo-friendly readable lines (`[ROUTING] requestId=… tier=…`, `[EXECUTION] mode=… executionType=…`) and structured JSON events (`event=llm.request.completed`, `event=llm.fallback.used`, etc.). |
| `MetricsRecorder`           | Bumps Micrometer counters/timers tagged by tenant/provider/model/status/cache/fallback. |

### 1.9 Controllers (`com.orchestrix.controller`)

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `ChatCompletionController`  | `POST /v1/chat/completions`. Returns `SimpleChatResponse` for non-streaming, SSE flux for streaming. |
| `AdminController`           | `/admin/mode`, `/admin/mode/switch`, `/admin/provider/status`, `/admin/providers/{name}/fail`, `/admin/providers/{name}/recover`, `/admin/providers/health[/persisted]`. |
| `TenantController`          | `/tenant/state` (and legacy `/tenant/me`) returns identity + budget + buckets + current mode. `/tenant/requests` returns last 50 logs for the tenant. |
| `RootController`            | `/` returns the service status + active mode + endpoint map. |

### 1.10 Bootstrap & config

| Class                       | What it does                                                          |
|-----------------------------|------------------------------------------------------------------------|
| `OrchestrixApplication`     | Spring Boot entrypoint. `@EnableAsync`, `@EnableScheduling` for the periodic health flush. |
| `OrchestrixProperties`      | All `@ConfigurationProperties`-bound knobs: mode, cache, routing weights and thresholds, resilience timeouts, per-provider config, fallback chain, failure-injection toggle. |
| `WebClientConfig`           | Single shared `WebClient.Builder` for all provider adapters with Netty-level connect/read/write timeouts. |
| `WebConfig`                 | Registers `ApiKeyAuthFilter` against the right URL patterns. |
| `Resilience4jConfig`        | Already covered above. |
| `TenantBootstrap`           | Dev/test only: seeds two demo tenants on first run with deterministic API keys. |

---

## 2. Full request lifecycle

Walk through a non-streaming chat request, step by step.

```
HTTP request:
  POST /v1/chat/completions
  Authorization: Bearer orx_acme_demo_key_123
  Content-Type: application/json
  { "messages": [{"role":"user","content":"Explain CAP theorem briefly."}] }
```

### Step 1 — Filter
`ApiKeyAuthFilter#doFilterInternal`:
1. Extract `requestId` from `X-Request-Id` header or generate one.
2. Put `requestId` and (after lookup) `tenantId` into MDC so every downstream
   log line carries them.
3. Hash the raw bearer token; look up the tenant by hash.
4. Reject with 401 if missing/invalid; 403 if `tenant.enabled=false`.
5. Set `TenantContext`. Pass through. Clear in `finally`.

### Step 2 — Controller
`ChatCompletionController#chat`:
- Validates the `ChatRequest` body (`@Valid`).
- Reads `TenantContext.require()`.
- For non-streaming, calls `completionService.complete(tenant, request)` and
  maps the resulting `ChatResponse` into a `SimpleChatResponse`.

### Step 3 — Rate limit
`RateLimitService.enforce(tenant)`:
- Per-tenant dual token bucket (RPS + RPM).
- Throws `RateLimitExceededException` → mapped to 429 by
  `GlobalExceptionHandler`.

### Step 4 — Budget gate
`BudgetService.enforceBudget(tenant)`:
- Reads `tenant_usage` for today.
- Throws `BudgetExceededException` → 402 if `daily_spent ≥ daily_budget`.
- Soft signals (`isNearBudgetLimit`, `isOverBudget`) are read later by the
  routing engine for tier overrides.

### Step 5 — Cache lookup (non-streaming only)
`CacheService.get(tenant, request, tier)`:
- Key: `sha256(tenantId | tier | messages)`. Tier is part of the key so a
  rerouted prompt doesn't collide.
- Returns the cached `ChatResponse` if valid; recorded as `cacheHit=true`,
  metrics bump, persisted RequestLog with `cacheHit=true` flag.
- Mock results are **not cached**, so a switch from MOCK to REAL won't keep
  serving stale mocks.

### Step 6 — Routing decision
`RoutingEngine.route(tenant, request)`:
1. `FeatureExtractor.extract(messages)` → `PromptFeatures`.
2. Build `eligible` providers: in `orchestrix.fallback-chain` order, intersect
   with `tenant.allowed_providers`, drop providers whose breaker is OPEN.
3. Compute `complexity`, `cost(eligible[0])`, `reliability(eligible[0])`,
   `final = 0.5 C + 0.3 R - 0.2 K`.
4. Map `final` → tier via thresholds.
5. Apply overrides: tenant ceiling → near-budget LOW → over-budget LOW.
6. Pick primary provider for the tier (LOW prefers Ollama; otherwise
   first eligible). Fallback chain = remaining eligibles.
7. Resolve model name via `provider.resolveModel(tier)`.
8. Return `RoutingDecision { primary, primaryModel, tier, fallback,
   complexity, cost, reliability, final, reason, override }`.

Logs emitted:
```
[ROUTING] requestId=… tier=MID score=2.972 provider=OPENAI
{"event":"llm.routing.decision", …}
```

### Step 7 — Executor (mode-aware leaf)
For each provider in `[primary, ...fallback]`:

`ProviderExecutor.execute(provider, invocation, tier)`:
- `mode = MOCK`        → return mock immediately.
- `mode in {REAL, HYBRID}` and `!provider.isConfigured()` → mock with reason
  `"credentials missing"`.
- Otherwise call `ResilientProviderInvoker.invoke(provider, invocation)`:
  - Per-provider `CircuitBreaker` + `Retry` (exponential backoff) + 30s
    timeout.
  - Records success/failure into `ProviderHealthTracker`.
- `mode == HYBRID`: any failure becomes a mock with reason
  `"real call failed: <classification>"`.

Returns `ExecutionResult { provider, model, response, executionType, reason }`.

Logs emitted on each attempt:
```
[EXECUTION] requestId=… mode=HYBRID provider=OPENAI executionType=MOCK reason="credentials missing"
```

### Step 8 — Orchestrator handling
Back in `ChatCompletionService.tryNext`:

- On success (`ExecutionResult` returned):
  - Build `ChatResponse`, set `tier`, `mode`, `executionType`, `reason`.
  - Cache the response only if `executionType == REAL`.
  - `BudgetService.recordSpend(tenant, cost, totalTokens)` — cost is 0 for
    mocks so they don't burn budget.
  - `MetricsRecorder.recordRequest(...)`.
  - `StructuredLogger.requestCompleted(...)` — JSON event.
  - `persistRequestLog(...)` — writes one `request_logs` row.
  - Return the response.

- On failure (the executor itself errored — only possible in REAL mode):
  - `StructuredLogger.fallbackUsed(from, to, reason)`.
  - `MetricsRecorder.recordFallback(...)`.
  - Recurse with `idx + 1`. If the chain is exhausted, persist an `error`
    log row and throw `NoProviderAvailableException` → 503.

### Step 9 — Controller response
Controller maps the `ChatResponse` to a `SimpleChatResponse`:

```json
{
  "requestId": "9b1f-…",
  "tier": "MID",
  "provider": "OPENAI",
  "model": "gpt-4o-mini",
  "mode": "MOCK",
  "response": "MOCK: OpenAI response for MID tier",
  "cost": 0,
  "latencyMs": 12,
  "fallbackUsed": false,
  "fallbackChain": "OPENAI",
  "reason": "credentials missing",
  "status": "completed"
}
```

### Streaming variant
For `stream: true`, the lifecycle is the same up through Step 6. Then:

- `ProviderExecutor.executeStream(...)` returns a `Flux<StreamExecution>`
  carrying chunks + executionType + reason.
- `ChatCompletionService.streamWithFallback` collects chunks, watches for
  errors, and:
  - If the stream errors **before any chunk has been emitted** and there's
    still a provider in the fallback chain, recurse to the next provider.
  - If chunks have already been emitted, the stream ends with `event: error`
    and `status="incomplete_stream"` — we never emit duplicate tokens.
- `doOnComplete` writes the persisted RequestLog row (with the full
  collected content) and the structured "completed" event.
- Controller wraps each chunk into an SSE `event: token` and adds a 15s
  heartbeat comment so proxies don't drop the connection.

---

## 3. Sample API outputs

### 3.1 Successful HYBRID call (no API key set, falls back to mock)

Request:
```http
POST /v1/chat/completions
Authorization: Bearer orx_acme_demo_key_123

{ "messages": [{"role":"user","content":"hi"}] }
```

Logs (terminal):
```
[ROUTING] requestId=f751929a-… tier=LOW score=0.902 provider=OLLAMA
[EXECUTION] requestId=f751929a-… mode=HYBRID provider=OLLAMA executionType=MOCK reason="real call failed: OLLAMA:Connection refused"
{"event":"llm.routing.decision","requestId":"f751929a-…","tier":"LOW","complexity":0.005,"cost":0.0,"reliability":3.0,"finalScore":0.9,"overrideApplied":false}
{"event":"llm.request.completed","requestId":"f751929a-…","tenantId":"tenant-acme","provider":"OLLAMA","model":"llama3.2","latencyMs":7,"promptTokens":12,"completionTokens":24,"totalTokens":36,"costUsd":0.0,"routingScore":0.9,"fallbackUsed":false,"fallbackChain":"OLLAMA","cacheHit":false,"status":"completed"}
```

Response:
```json
{
  "requestId": "f751929a-…",
  "tier": "LOW",
  "provider": "OLLAMA",
  "model": "llama3.2",
  "mode": "MOCK",
  "response": "MOCK: Ollama response for LOW tier",
  "cost": 0,
  "latencyMs": 7,
  "fallbackUsed": false,
  "fallbackChain": "OLLAMA",
  "reason": "real call failed: OLLAMA:Connection refused",
  "status": "completed"
}
```

### 3.2 Architecture-level prompt → MID tier

Request:
```http
POST /v1/chat/completions

{ "messages": [{"role":"user","content":"Design a high-availability event-driven kafka architecture with sharding"}] }
```

`[ROUTING]`:
```
[ROUTING] requestId=… tier=MID score=2.418 provider=OPENAI
```

In MOCK mode the response would be:
```
"response": "MOCK: OpenAI response for MID tier"
```

### 3.3 Fallback chain (REAL mode, primary fails)

Logs when OPENAI fails 3 retries and OLLAMA succeeds:
```
[ROUTING] requestId=… tier=MID score=2.418 provider=OPENAI
{"event":"llm.fallback.used","fromProvider":"OPENAI","toProvider":"OLLAMA","reason":"OPENAI:HTTP 503"}
[EXECUTION] requestId=… mode=REAL provider=OLLAMA executionType=REAL
{"event":"llm.request.completed","provider":"OLLAMA",...,"fallbackUsed":true,"fallbackChain":"OPENAI->OLLAMA",...}
```

### 3.4 Streaming response (HYBRID, real upstream)

```
event: token
data: {"delta":"CAP","done":false}

event: token
data: {"delta":" theorem says","done":false}

…

event: token
data: {"delta":"","done":true,"finishReason":"stop","promptTokens":12,"completionTokens":42}
```

### 3.5 Streaming with mock fallback (HYBRID, upstream down)

If OLLAMA is down and we're in HYBRID, the executor's stream falls back to
the mock stream:

```
event: token
data: {"delta":"MOCK: Ollama response for LOW tier","done":false}

event: token
data: {"delta":"","done":true,"finishReason":"stop","promptTokens":12,"completionTokens":24}
```

Persisted row:
```
provider=OLLAMA  model=llama3.2  status=completed  mode=HYBRID
execution_type=MOCK  execution_reason="real stream failed: OLLAMA:Connection refused"
streamed=true  fallback_used=false
```

---

## 4. Routing formula — both ways

### 4.1 Plain English

We score the request on three axes:

- **Complexity** is "how hard is the prompt?" — long, code-heavy, or
  architecture/debugging questions push it up.
- **Reliability** is "how trustworthy is the cheapest healthy provider right
  now?" — derived from the rolling failure rate, average latency, and the
  circuit-breaker state.
- **Cost** is "how much would this request cost on the cheapest provider?" —
  in raw USD.

Final score = half of complexity, plus a third of reliability, minus a fifth
of cost. We then bucket:

- ≤ 1.5 → LOW (Ollama)
- ≤ 3.5 → MID (gpt-4o-mini)
- &gt; 3.5 → HIGH (gpt-4o or claude-3-5-sonnet)

Then four overrides clamp the choice: tenant tier ceiling → near-budget →
over-budget → provider eligibility (allowed by tenant + circuit closed).

### 4.2 Technical

```text
norm_tokens     = min(10, tokens_estimate / 150)
length_factor   = min(1.0, prompt_chars / 2000)

complexity (C)  = norm_tokens * 0.3
                + (has_code         ? 2 : 0)
                + (is_architecture  ? 3 : 0)
                + (is_debugging     ? 2 : 0)
                + length_factor

cost (K)        = (prompt_tokens/1000)  * cost_per_1k_input
                + (completion_tokens/1000) * cost_per_1k_output
                  // approximated as 2 * tokens * input_rate at decision time

health          = 1.0
                - min(0.5, failure_rate * 0.5)
                - (avg_latency > 2000ms ? min(0.4, (avg_latency-2000)/10000) : 0)
health         *= cb_state == OPEN ? 0 : (cb_state == HALF_OPEN ? 0.5 : 1.0)
reliability (R) = health * 3
                - (avg_latency > 1500ms ? min(0.5, (avg_latency-1500)/5000) : 0)
                - min(0.5, failure_rate * 0.5)

FINAL_SCORE     = (C * 0.5) + (R * 0.3) - (K * 0.2)
```

### 4.3 Worked examples

| Prompt                                                           | C    | R   | K (USD) | FINAL | Tier |
|------------------------------------------------------------------|------|-----|---------|-------|------|
| `"hi"`                                                            | 0.01 | 3.0 | 0.0     | 0.91  | LOW  |
| `"Explain CAP theorem briefly."`                                  | 0.07 | 3.0 | 0.0001  | 0.93  | LOW  |
| `"Design a kafka-based event-driven architecture with sharding"` | 3.08 | 3.0 | 0.0001  | 2.44  | MID  |
| Long arch+code+debug prompt (1500 chars)                         | ≈8   | 3.0 | 0.001   | ≈4.9  | HIGH |
| Same as above, but tenant `max_model_tier=MID`                   | ≈8   | 3.0 | 0.001   | ≈4.9 → MID | MID (ceiling) |
| Same, but tenant at 95% budget                                    | ≈8   | 3.0 | 0.001   | ≈4.9 → LOW | LOW (near budget) |

The score-breakdown is persisted on every `request_logs` row so you can
audit decisions historically — that's how you'd answer "why did this prompt
not get gpt-4o yesterday?".

---

## 5. Quick map: file ↔ responsibility

```
Routing             RoutingEngine + ScoringService + FeatureExtractor
Mode dispatch       ProviderExecutor + ModeService + MockResponseFactory
Real call           ResilientProviderInvoker + Resilience4jConfig
Provider HTTP       OpenAIProvider | OllamaProvider | AnthropicProvider
Tenant gate         ApiKeyAuthFilter + TenantContext + ApiKeyHasher
Tenant policy       BudgetService + RateLimitService
Persistence         JPA entities + Spring Data repositories + V1/V2 migrations
Observability       StructuredLogger + MetricsRecorder
Demo controls       AdminController (mode, providers, failure injection)
```

When something misbehaves, the table above is the fastest way to find the
class that owns the behavior. Next stop after that is the integration tests
under `src/test/java/com/orchestrix/integration` — they cover the most
common end-to-end paths.
