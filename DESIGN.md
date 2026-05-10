# Orchestrix Design

This document explains why Orchestrix is built the way it is — the problem it
solves, the architecture, the scoring engine, the **mode-switching**
indirection, the failure modes we considered, the tradeoffs we accepted, what
we deliberately did not build, and how the system would scale from
10 → 100k RPS.

---

## 1. Problem framing

A team running multiple LLM-backed features ends up wanting most of these:

- Per-feature cost ceilings.
- Per-customer rate limits and quotas.
- Resilience to a single provider outage.
- Right-sized model selection (don't pay GPT-4 prices for "what's 2+2").
- A single observability surface across all providers.
- The ability to add or swap providers without changing client code.
- A safe way to demo and test without burning real API budget.

Doing each of these as one-off code on the application side leads to drift,
inconsistent behavior across services, and "did anyone update the OpenAI
fallback in the recommendations service?" incidents.

**Orchestrix is the shared seam.** Clients send a generic chat request; the
gateway picks the best provider/model for *this* request, *this* tenant,
*right now*, executes it in the active mode (real or mock), and is honest
about cost, latency, and failures.

---

## 2. Architecture

```
┌──────────┐
│ Client   │  POST /v1/chat/completions
└────┬─────┘
     │ Authorization: Bearer <key>
     ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ApiKeyAuthFilter                                                    │
│   • SHA-256 hash → Tenant lookup                                    │
│   • Sets MDC (requestId, tenantId) and TenantContext                │
└────┬────────────────────────────────────────────────────────────────┘
     ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ChatCompletionController → ChatCompletionService                    │
│   1. RateLimitService.enforce        (per-tenant token bucket)      │
│   2. BudgetService.enforceBudget     (daily cap → 402)              │
│   3. CacheService.get                (REAL responses only)          │
│   4. RoutingEngine.route             (score + override → decision)  │
│   5. tryNext(provider chain)         → ProviderExecutor             │
│   6. Persist RequestLog + metrics + structured log                  │
└────┬────────────────────────────────────────────────────────────────┘
     ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ProviderExecutor (mode-aware leaf)                                  │
│   ModeService.current() ∈ { MOCK, REAL, HYBRID }                    │
│                                                                     │
│   MOCK   → MockResponseFactory.build(provider, tier)                │
│   REAL   → if !provider.isConfigured() → mock("credentials missing")│
│            else                        → ResilientProviderInvoker  │
│   HYBRID → if !provider.isConfigured() → mock("credentials missing")│
│            else realCall.onErrorResume → mock("real call failed: …")│
└────┬────────────────────────────────────────────────────────────────┘
     ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ResilientProviderInvoker (real-call wrapper)                        │
│   per provider:                                                     │
│     • CircuitBreaker (Resilience4j)                                 │
│     • Retry with exponential backoff (non-streaming only)           │
│     • Hard timeout (30s default)                                    │
│   updates ProviderHealthTracker on every outcome                    │
└────┬────────────────────────────────────────────────────────────────┘
     ▼
┌──────────────┬───────────────┬─────────────────┐
│ OpenAI       │ Ollama        │ Anthropic       │  (LLMProvider impls)
│ adapter      │ adapter       │ adapter         │
└──────────────┴───────────────┴─────────────────┘
        ▲
        │  failure-injection registry intercepts here
        │  (admin endpoints flip behavior at runtime)

Persistence (MySQL + Flyway):
  tenants, request_logs (+ mode/execution_type), provider_health, tenant_usage

Observability:
  • Demo-friendly [ROUTING]/[EXECUTION] log lines
  • Structured JSON events (logstash-logback-encoder)
  • Prometheus metrics (Micrometer)
  • Per-provider health and execution-type via /admin endpoints
```

### Layering

| Layer            | Examples                                                    |
|------------------|-------------------------------------------------------------|
| Controller       | `ChatCompletionController`, `AdminController`, `TenantController` |
| Service          | `ChatCompletionService`, `BudgetService`, `RateLimitService`, `CacheService` |
| **Mode**         | `ModeService`, `ProviderExecutor`, `MockResponseFactory`, `ExecutionMode/Type` |
| Routing          | `RoutingEngine`, `ScoringService`, `FeatureExtractor`, `CostCalculator` |
| Provider adapter | `LLMProvider` interface + `OpenAIProvider`, `OllamaProvider`, `AnthropicProvider` |
| Resilience       | `ResilientProviderInvoker`, `Resilience4jConfig`            |
| Persistence      | JPA entities + Spring Data repositories                     |
| Observability    | `StructuredLogger`, `MetricsRecorder`                       |

The provider abstraction is the **adapter pattern** — each implementation owns
its HTTP shape, error mapping, streaming format, token accounting, and a
`isConfigured()` check that decides whether REAL/HYBRID can attempt a real
call or must fall through to a mock.

---

## 3. Routing strategy

### 3.1 The formula

For a single request:

```
Complexity (C) = (norm_tokens * 0.3)
               + (is_code         * 2)
               + (is_architecture * 3)
               + (is_debugging    * 2)
               + length_factor

Cost (K)        = estimated_tokens * price_per_token   (USD)
Reliability (R) = (health * 3) - latency_penalty - failure_rate_penalty

FINAL = (C * 0.5) + (R * 0.3) - (K * 0.2)
```

| Tier | Score range  | Default model                  |
|------|--------------|--------------------------------|
| LOW  | ≤ 1.5        | `llama3.2` via Ollama          |
| MID  | 1.5 to 3.5   | `gpt-4o-mini`                  |
| HIGH | > 3.5        | `gpt-4o` / `claude-3-5-sonnet` |

### 3.2 Why these specific numbers?

The original spec said "0..3, 4..7, 8+" with `tokens * 0.3` (raw tokens). With
real prompts that hits absurd numbers — 1000 tokens contributes 300 by itself,
forcing every meaningful prompt to HIGH regardless of intent. Two changes:

1. **Normalize the token term**: we map raw tokens to a 0..10 saturating scale,
   so the token component contributes 0..3 (capping at ~1500 tokens of prompt).
   This makes the *intent* signals (code, architecture, debugging) meaningful
   rather than dominated by length.
2. **Recalibrate the bands** to the resulting natural range (~0.5..~5.5). A
   trivial chat lands at LOW, an architecture/debug query lands at MID, and
   the high tier is reserved for prompts hitting multiple complexity signals.

The thresholds are properties (`orchestrix.routing.score-low-tier-max`,
`score-mid-tier-max`), so re-calibrating in production is a config change.

### 3.3 Override layer

After the score-derived tier is computed, four overrides apply in order:

1. **Tenant tier ceiling** — `tenant.max_model_tier = MID` clamps any HIGH
   score to MID. Records `overrideReason=tenant-tier-ceiling=MID`.
2. **Near budget (≥ 90%)** — Forces LOW. Avoids burning the last $5 of a
   tenant's daily budget on a single GPT-4 request.
3. **Over budget (≥ 100%)** — `BudgetService.enforceBudget` returns 402 *and*
   any leftover requests are routed at LOW.
4. **Provider eligibility** — Only providers in
   `tenant.allowed_providers` whose circuit breaker is not OPEN make it into
   the chain.

### 3.4 Provider selection within tier

| Tier | Default primary preference | Reasoning                                        |
|------|----------------------------|--------------------------------------------------|
| LOW  | Ollama (free, local)       | Cheapest viable option for trivial prompts.      |
| MID  | First eligible hosted      | Hosted MID models beat Ollama on quality at a tolerable cost. |
| HIGH | First eligible hosted      | Premium quality is the whole point.              |

Fallbacks are filled with the remaining eligible providers in the configured
order (`orchestrix.fallback-chain`).

### 3.5 Worked example

Request: "Why does this Kafka consumer keep dying with NullPointerException?"
(includes a code block).

```
features.estimatedTokens = 110
features.hasCode         = true
features.isArchitecture  = false
features.isDebugging     = true
features.lengthFactor    = 0.06

complexity = (0.22 * 0.3) + (1*2) + (0*3) + (1*2) + 0.06
           ≈ 4.13

cost (OPENAI, ~110 tokens at gpt-4o-mini pricing) ≈ 0.0001 USD
reliability (clean state) ≈ 3.0

FINAL = (4.13 * 0.5) + (3.0 * 0.3) - (0.0001 * 0.2)
      ≈ 2.07 + 0.9
      ≈ 2.97          → MID tier, route to gpt-4o-mini
```

---

## 4. Mode-switching architecture

The mode system is an **indirection at the leaf**: routing, fallback chain,
retries, circuit breakers, rate limits, and budgets are all unchanged.
The only thing the mode controls is whether each individual provider attempt
is satisfied by the upstream API or by a deterministic mock.

### 4.1 Components

| Component                | Responsibility                                                     |
|--------------------------|---------------------------------------------------------------------|
| `ExecutionMode` enum     | `MOCK` / `REAL` / `HYBRID` — system-wide setting.                  |
| `ExecutionType` enum     | `MOCK` / `REAL` — how a single attempt actually resolved.          |
| `ModeService`            | Holds the current mode in an `AtomicReference`. Runtime-switchable. |
| `MockResponseFactory`    | Tier-aware deterministic mock content + mock streaming.            |
| `ProviderExecutor`       | Mode-aware dispatcher. Wraps `ResilientProviderInvoker`.           |
| `LLMProvider#isConfigured()` | Per-provider check: are local prerequisites for a real call met? |

### 4.2 Resolution per attempt

```
input  : LLMProvider p, ProviderInvocation inv, ModelTier t
output : ExecutionResult { provider, model, response, executionType, reason }

mode = modeService.current()

if mode == MOCK:
    return mock(p, t, "mode=MOCK")

if !p.isConfigured():
    return mock(p, t, "credentials missing")

real = invoker.invoke(p, inv)        // retries + circuit breaker

if mode == HYBRID:
    real = real.onErrorResume(e -> mock(p, t, "real call failed: " + e))

return real
```

### 4.3 Why the executor is the right seam

We considered three places to inject the mock/real choice:

| Option                                 | Why we rejected it                                         |
|---------------------------------------|------------------------------------------------------------|
| Inside each `LLMProvider`             | Each adapter would re-implement the same mode logic.       |
| In `ChatCompletionService.tryNext`    | Mixes orchestration with execution mechanics — hard to test. |
| `ProviderExecutor` between them ✅     | Single class owns the mode decision; orchestrator stays clean. |

Concrete benefits:

- `ChatCompletionService` is unaware of mode — its tests don't need to be
  rewritten when modes are added.
- `ProviderExecutor` tests are tiny and focused (see `ProviderExecutorTest`).
- New modes (e.g., RECORD/REPLAY for offline captures) can be added by
  extending the executor without touching anything upstream.

### 4.4 Cache interaction

Mock responses are **never cached**. Otherwise a switch from MOCK → REAL
would keep serving the cached mock for that prompt+tier. We cache only when
`executionType == REAL`, which is also the more honest invariant: cache is
for "real, expensive answers".

### 4.5 Cost reporting under modes

Mock responses have `cost = 0.0`. This is intentional — the mock didn't burn
any real tokens, so cost dashboards reflect actual spend. The `executionType`
column on `request_logs` lets you reconstruct "what fraction of traffic was
real vs mock" historically.

---

## 5. Failure modes considered

| Scenario                                | What Orchestrix does                                                                |
|-----------------------------------------|-------------------------------------------------------------------------------------|
| **Provider HTTP 5xx**                   | Retry with exponential backoff (3 attempts). Failures contribute to circuit breaker. In HYBRID, after retries exhaust, mock served for that provider. In REAL, falls through to next provider. |
| **Provider HTTP 429**                   | Treated as retryable. If sustained, breaker opens and Orchestrix falls back. |
| **Provider request timeout (>30s)**     | `Mono.timeout` aborts; counted as failure; behavior matches above.                |
| **Provider goes fully down**            | Breaker trips OPEN; routing engine excludes it from new requests until HALF_OPEN. |
| **All providers down (REAL)**           | Returns 503 NoProviderAvailableException with the attempted chain.                |
| **All providers down (HYBRID)**         | Each provider's failure is converted to a mock — request always succeeds.         |
| **Tenant abuse (RPS spike)**            | Per-tenant token bucket throws 429. Other tenants unaffected.                     |
| **Tenant runs out of budget**           | 402 BudgetExceededException. Routing forced to LOW for any slack still allowed.   |
| **DB transient failure on log write**   | Caught and warn-logged; user response is unaffected.                              |
| **Cache (Redis) unreachable**           | CacheService falls back to bounded in-memory LRU; logs a debug line.              |
| **Mode flip mid-flight**                | The next request honors the new mode (atomic reference). In-flight requests complete with the previous mode — no abort. |
| **Streaming connection drops mid-tokens**| Mid-stream errors do **not** trigger fallback (would emit duplicate tokens). The stream ends with `event: error` and the persisted RequestLog has `status="incomplete_stream"`. |
| **Two parallel requests hit cold cache**| Cache is "miss-then-fill"; worst case both call the provider once. Acceptable to avoid lock latency. |
| **Provider succeeds but returns broken JSON in stream** | Each chunk parsed independently; malformed line skipped (`debug` log). Stream completes with valid tokens. |
| **Tenant with disabled flag**           | Auth filter returns 403 before routing runs.                                      |
| **Failure-injection ALWAYS_ERROR mode** | Every attempt fails. In HYBRID → mocks every provider. In REAL → 503 after chain exhausts. |

---

## 6. Tradeoffs

### 6.1 In-process state vs. distributed state

| Component               | Where state lives | Why                                           | What it costs at scale                  |
|-------------------------|-------------------|-----------------------------------------------|-----------------------------------------|
| Rate-limit buckets      | In-memory map     | Simplicity, microsecond latency               | Limits aren't global across replicas    |
| Mode                    | In-memory `AtomicReference` | Atomic flip; no DB hop on hot path  | Each replica must be flipped separately |
| Provider health stats   | In-memory + flushed to MySQL every 30s | Same                | A replica restart loses live counters until DB reload |
| Cache (default)         | In-memory LRU     | Works without Redis                           | Per-replica cache hit rate              |
| Cache (Redis)           | Redis             | Global hit rate                               | One more dependency to operate          |
| Circuit breaker state   | In-memory (Resilience4j) | Standard pattern                       | Each replica independently learns of failure |
| Tenant + budget         | MySQL             | Source of truth                                | Reads cached implicitly via Hikari + Hibernate L1 |

For mode specifically, multi-replica deployments would either (a) propagate
flips via a config service / Redis pub-sub, or (b) accept that each replica
flips independently when the operator hits its `/admin/mode/switch`. We chose
the simpler local-flip semantics for the demo.

### 6.2 Servlet stack + reactive providers

We use the servlet stack (Spring MVC) for the gateway and reactive WebClient
for upstream calls. The hybrid keeps the controller surface familiar and lets
each request use a thread per request, while LLM calls (which dominate
latency) are non-blocking. SSE is delivered via `Flux<ServerSentEvent>`.

### 6.3 Routing decision uses a single candidate

`RoutingEngine` picks the first eligible provider as the "candidate" for cost
and reliability scoring, then derives the tier and re-selects the actual
provider. This means the cost dimension uses one provider's price during tier
selection. For the LOW tier we then deliberately swap to Ollama (free), so the
practical impact is small.

### 6.4 Mid-stream failures don't fall back

If a provider starts streaming and fails halfway through, we end the stream
with `incomplete_stream` rather than restarting on the fallback. Restarting
would emit duplicate tokens. Clients can retry the request if they want a
clean run.

### 6.5 Mocks are tier-aware, not prompt-aware

The mock content is `"MOCK: <provider> response for <tier> tier"` — it does
not reflect the prompt content. This is deliberate:

- It's deterministic, so tests can assert on the exact string.
- It surfaces both the provider and the tier so demos can verify routing.
- A prompt-aware mock would either be a tiny LLM (defeating the "no key needed"
  goal) or a templated parrot that's worse than nothing.

### 6.6 Mock responses are zero-cost

We don't simulate tokens-burned in mock mode. Cost dashboards reflect *real*
spend; mock traffic is invisible to budgets. The alternative — charging a
synthetic cost for mocks — would distort budget enforcement during tests.

---

## 7. What was NOT built (honest cuts)

- **Web UI / dashboard.** Metrics are exposed via Prometheus; observability
  belongs in Grafana, not in this codebase.
- **Per-tenant API key rotation flow.** Hashing is in place; rotation is a
  CRUD endpoint we didn't add.
- **OAuth / mTLS auth.** Auth is a single bearer token. Production deployments
  would gate `/admin/*` behind a separate auth posture.
- **Multi-instance global rate limiting.** Buckets are per-instance.
- **Multi-region failover.** Single deployment per region.
- **Cost reconciliation against provider bills.**
- **PII redaction / DLP.** Prompts are logged structurally only as event
  metadata; raw content is not logged.
- **Provider request signing / proxying secrets.** API keys are read from env;
  no integrated secret manager.
- **Anthropic full SSE streaming.** The Anthropic adapter currently emits a
  single chunk derived from the non-streaming response. Real Anthropic SSE
  involves typed events we'd parse in production.
- **Backpressure-aware streaming.** Reactor handles the producer side; we
  don't currently signal slow clients back to the provider.
- **Admin authorization tiers.** Any authenticated tenant can hit
  `/admin/*`. In production these endpoints are role-gated.
- **Tenant CRUD UI/API.** Tenants are bootstrapped or created by SQL.
- **Per-tenant mode override.** Mode is global, not per-tenant.
- **Distributed mode propagation.** Multi-replica clusters need an external
  pub-sub to keep modes in sync.
- **Recorded-replay mode.** Easy to add (capture real responses, replay), but
  out of scope for this iteration.

---

## 8. Production gap analysis

| Gap                                | Why it matters                                                                |
|-----------------------------------|--------------------------------------------------------------------------------|
| Real tokenizer per provider       | Estimated tokens are off by 5-15%, which compounds in cost dashboards.        |
| Distributed rate limiting          | Single-instance buckets allow burst-through across replicas.                  |
| Distributed mode propagation      | Operators must flip every replica or accept staggered rollout.                |
| Admin auth                        | `/admin/*` should require a different role/credential than tenant API.        |
| Secrets handling                  | API keys read from env; production wants a vault.                             |
| Audit logging                     | Today we log structured events; for SOC2 we'd add immutable audit trails.     |
| PII redaction at the edge         | Prompts may contain customer data; production would scrub before sending.     |
| Connection pooling per provider   | Single shared `WebClient.Builder`; per-provider sizing helps under high RPS.  |
| Graceful degradation on DB outage | Rate limits / budgets currently read from DB. A read-through cache helps.    |
| Schema versioning / blue-green    | Two Flyway migrations today; we'd want an "expand/contract" workflow.         |
| End-to-end testing harness        | Add contract tests that hit real provider stubs (e.g., WireMock recordings).  |
| SSE proxy survival                | Production load balancers can drop SSE connections — test with the actual edge layer. |
| Per-tenant mode                   | Some tenants may want REAL while a beta tenant stays HYBRID. Currently global. |

---

## 9. Scaling strategy

### 10 RPS — single replica
Current shape is enough. Single Spring Boot process, MySQL, optional Redis.

### 1000 RPS — horizontally replicated
Bottlenecks at this scale:

1. **Per-instance rate limiting** becomes inaccurate. Migrate to Redis-backed
   Lua sliding window.
2. **Database write rate** for `request_logs`. Buffer through Kafka to a
   batch insert worker, or downsample.
3. **Mode propagation.** Add Redis pub-sub on `ModeService.setMode` so all
   replicas flip together.
4. **Provider connection pool sizing.** Set explicit pool sizes (200-500 per
   provider) in Netty.
5. **Health tracker contention.** Replace `synchronized` with `LongAdder`.
6. **Caching** — turn Redis on for cross-instance cache hits.

### 100k RPS — multi-region, sharded

1. Tenant lookup: 30s-TTL local cache → Redis cache → MySQL.
2. Rate limits: tenant-sharded across multiple Redis clusters; a few large
   tenants get their own bucket cluster.
3. Routing config moves to a versioned config store (Etcd / config service);
   replicas refresh in the background.
4. Provider abstraction stays the same, but each provider has a dedicated
   pool of replicas to keep blast radius isolated.
5. Async logging: `request_logs` stream into a pipeline (Kafka → ClickHouse).
6. Per-region failover. Cross-region routing is an explicit policy, not a
   silent fallback.
7. Backpressure: per-tenant in-flight caps; shed with `503 retry-after`.
8. SLO-driven reliability score: degrading-but-up providers get demoted before
   they fully break.
9. Per-tenant mode: extend `ModeService` to a tenant-keyed map.

The codebase is intentionally designed so each escalation is a local change:
rate limiting is one class, logging is one class, the routing score is one
method, and mode resolution is `ProviderExecutor`. The seams are where the
scale conversations happen.

---

## 10. Closing note

Orchestrix is small enough to read in an afternoon and shaped enough to ship
behind real traffic with the additions in §8. The non-obvious decisions —
normalized token term, mid-stream no-fallback, single-instance state by
default, hybrid servlet+reactive, mode-as-leaf-indirection, mocks-not-cached
— are documented above so the next maintainer doesn't have to guess.
