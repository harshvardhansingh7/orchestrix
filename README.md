# Orchestrix — Intelligent LLM Orchestration Gateway

Orchestrix is a multi-tenant LLM gateway that sits between your application and
several LLM providers (OpenAI, Ollama, Anthropic). It picks the right
provider/model per request based on prompt complexity, cost, provider health,
and tenant policy — and absorbs failures with retries, circuit breakers, and a
fallback chain.

It runs in three execution modes (MOCK / REAL / HYBRID) so you can demo it
**without any API keys** and switch to real providers with one config flip.

This is **not** an OpenAI wrapper. The client never picks a provider.

> Status: production-shaped reference. All behavior described here is wired and
> covered by tests. See [`DESIGN.md`](./DESIGN.md) for architecture and
> [`SYSTEM_DEEP_DIVE.md`](./SYSTEM_DEEP_DIVE.md) for a class-by-class
> walkthrough and example logs.

---

## Quick start (60 seconds, no API keys)

Prereqs: **Java 17**, **Maven 3.9+**.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

The default mode is **HYBRID** — the system tries real providers and falls
back to mocks when credentials/server are missing. So with no setup you'll get
mock responses immediately.

Confirm it's up:

```bash
curl http://localhost:8080/
```

Returns the current mode and a list of endpoints.

### Demo tenants (auto-seeded in dev/test)

| Tenant         | API key                       | Daily budget | Allowed providers          | Max tier |
|----------------|-------------------------------|--------------|----------------------------|----------|
| `tenant-acme`  | `orx_acme_demo_key_123`       | $50          | OPENAI, OLLAMA             | HIGH     |
| `tenant-globex`| `orx_globex_demo_key_456`     | $20          | OPENAI, ANTHROPIC, OLLAMA  | MID      |

---

## The three execution modes

The system-wide mode controls how each provider call is served. Routing,
fallback, rate limits, budgets, and tenant policy are unchanged across modes —
only the leaf "execute this attempt" decision changes.

| Mode       | Behavior                                                                                  |
|------------|-------------------------------------------------------------------------------------------|
| **MOCK**   | Always return a deterministic mock. No upstream calls. Demo-safe.                         |
| **REAL**   | Call the real provider. If credentials are missing for that provider, return a mock with reason. |
| **HYBRID** | Try real first; on any failure (missing creds, connection refused, timeout, error), return a mock for the same provider. |

Default is **HYBRID** so you can demo end-to-end without keys, then add keys
incrementally. Mode can be flipped at runtime — see below.

### How mocks look

```
OPENAI    → "MOCK: OpenAI response for MID tier"
OLLAMA    → "MOCK: Ollama response for LOW tier"
ANTHROPIC → "MOCK: Anthropic response for HIGH tier"
```

The tier in the message reflects whichever tier the routing engine picked, so
you can verify the routing decision made sense.

---

## API examples

All requests use `Authorization: Bearer <api-key>`.

### 1. Chat completion (non-streaming)

```bash
curl -s -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer orx_acme_demo_key_123" \
  -H "Content-Type: application/json" \
  -d '{
        "messages": [
          { "role": "user", "content": "Explain CAP theorem briefly." }
        ]
      }' | jq
```

Sample response (the demo-friendly shape):

```json
{
  "requestId": "9b1f...e1",
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

`mode` here is the **execution outcome** (MOCK or REAL). `reason` is populated
only when a mock was served — it tells you *why*.

### 2. Streaming (SSE)

```bash
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer orx_acme_demo_key_123" \
  -H "Content-Type: application/json" \
  -d '{
        "stream": true,
        "messages": [{ "role": "user", "content": "Write a haiku about caching." }]
      }'
```

Returns SSE events:

```
event: token
data: {"delta":"Quiet ","done":false}

event: token
data: {"delta":"cache hums","done":false}

event: token
data: {"delta":"","done":true,"finishReason":"stop","promptTokens":12,"completionTokens":24}
```

### 3. Switch mode at runtime

```bash
# Get current mode
curl -s http://localhost:8080/admin/mode \
  -H "Authorization: Bearer orx_acme_demo_key_123" | jq
# → {"mode":"HYBRID","validModes":["MOCK","REAL","HYBRID"]}

# Switch to MOCK
curl -s -X POST http://localhost:8080/admin/mode/switch \
  -H "Authorization: Bearer orx_acme_demo_key_123" \
  -H "Content-Type: application/json" \
  -d '{"mode":"MOCK"}' | jq
# → {"mode":"MOCK","switched":true}

# Switch back
curl -X POST http://localhost:8080/admin/mode/switch \
  -H "Authorization: Bearer orx_acme_demo_key_123" \
  -H "Content-Type: application/json" \
  -d '{"mode":"HYBRID"}'
```

The flip is atomic — every subsequent request honors the new mode. No restart
needed.

### 4. Provider status

```bash
curl -s http://localhost:8080/admin/provider/status \
  -H "Authorization: Bearer orx_acme_demo_key_123" | jq
```

Returns one row per provider:

```json
[
  {
    "provider": "OPENAI",
    "enabled": true,
    "configured": false,
    "circuitState": "CLOSED",
    "healthScore": 1.0,
    "avgLatencyMs": 0.0,
    "failureRate": 0.0,
    "realCallPossible": false
  }
]
```

`configured=false` means "no API key" — REAL/HYBRID will mock for this provider.
Set the env var and restart to get `configured=true`.

### 5. Tenant state (with current mode)

```bash
curl -s http://localhost:8080/tenant/state \
  -H "Authorization: Bearer orx_acme_demo_key_123" | jq
```

Returns tenant identity, daily budget, spend, allowed providers, max tier,
rate-limit bucket snapshot, and the active execution mode.

### 6. Recent requests

```bash
curl -s http://localhost:8080/tenant/requests \
  -H "Authorization: Bearer orx_acme_demo_key_123" | jq
```

Last 50 request logs for the calling tenant — provider used, model, tokens,
cost, mode, executionType, fallback chain. Useful to verify routing/fallback
behaved as expected.

### 7. Failure injection (for testing resilience)

```bash
# Make OpenAI fail for the next 5 calls
curl -X POST http://localhost:8080/admin/providers/openai/fail \
  -H "Authorization: Bearer orx_acme_demo_key_123" \
  -H "Content-Type: application/json" \
  -d '{"mode":"ERROR","burst":5,"delayMs":200}'

# Recover
curl -X POST http://localhost:8080/admin/providers/openai/recover \
  -H "Authorization: Bearer orx_acme_demo_key_123"

# View live health
curl http://localhost:8080/admin/providers/health \
  -H "Authorization: Bearer orx_acme_demo_key_123"
```

Failure modes:

| Mode           | Behavior                                                     |
|----------------|--------------------------------------------------------------|
| `ERROR`        | Return a transient error for the next `burst` calls.         |
| `ALWAYS_ERROR` | Return errors until cleared via `/recover`.                  |
| `SLOW`         | Add a per-call delay of `delayMs`.                           |
| `TIMEOUT`      | Block long enough to trip the request timeout (~30s).        |

In **HYBRID** mode, these failures convert to mock responses with a reason
field. In **REAL** mode, they bubble through the existing fallback chain to
the next eligible provider.

---

## How routing works (plain English)

Every request, Orchestrix scores three things:

- **Complexity (C)** — How hard is the prompt? Long prompts, code blocks,
  architecture/design questions, and debugging requests bump complexity up.
- **Reliability (R)** — How healthy is each provider right now? Failure rate,
  latency, and circuit-breaker state.
- **Cost (K)** — Estimated USD cost for the request based on token count.

Final routing score:

```
FINAL = (C * 0.5) + (R * 0.3) - (K * 0.2)
```

Score → tier → provider:

| Score range | Tier  | Default model              |
|-------------|-------|----------------------------|
| ≤ 1.5       | LOW   | Ollama (`llama3.2`)       |
| 1.5 – 3.5   | MID   | `gpt-4o-mini`              |
| > 3.5       | HIGH  | `gpt-4o` / `claude-3-5-sonnet` |

Then we apply overrides:

- **Tenant tier ceiling** — `tenant.max_model_tier` clamps the chosen tier.
- **Near budget** (≥ 90% of daily) — Forces LOW tier.
- **Over budget** (≥ 100%) — Hard cap (HTTP 402).
- **Allowed providers** — Tenant must list the provider.
- **Provider health** — OPEN circuit breaker excludes the provider.

If the primary attempt fails, the fallback chain takes over (configurable, default `OPENAI → ANTHROPIC → OLLAMA`).

See [`DESIGN.md`](./DESIGN.md) §3 for the full derivation.

---

## How fallback works

Two layers of fallback, in this order:

1. **Within a provider** — Resilience4j retries with exponential backoff
   (max 3 attempts), and a circuit breaker trips after sustained failures.
2. **Across providers** — If the primary provider exhausts its retries, the
   orchestrator moves to the next provider in the routing decision's fallback
   chain. Each fallback attempt is separately retried + circuit-broken.

For streaming, fallback only happens *before* the first token is emitted —
mid-stream failures end the stream as `incomplete_stream` so clients never see
duplicated tokens.

In **HYBRID** mode, an extra layer kicks in at the leaf: a single failed real
call is converted into a mock for the same provider, so even a fully-down
provider gives a usable response.

---

## Logs and where to find them

Two parallel log streams emit on every request:

### Demo-friendly readable lines (visible in the terminal)

```
[ROUTING] requestId=9b1f… tier=MID score=2.418 provider=OPENAI
[EXECUTION] requestId=9b1f… mode=HYBRID provider=OPENAI executionType=MOCK reason="credentials missing"
```

### Structured JSON events (for log aggregation)

```json
{
  "event": "llm.request.completed",
  "requestId": "9b1f…",
  "tenantId": "tenant-acme",
  "provider": "OPENAI",
  "model": "gpt-4o-mini",
  "latencyMs": 12,
  "promptTokens": 12,
  "completionTokens": 24,
  "totalTokens": 36,
  "costUsd": 0.0,
  "routingScore": 2.418,
  "fallbackUsed": false,
  "fallbackChain": "OPENAI",
  "cacheHit": false,
  "status": "completed"
}
```

Routing decisions, fallbacks, and execution mode resolutions all emit their
own structured events keyed on `event` so log pipelines can index them.

In dev/test profiles you get the human-readable Logback pattern; in other
profiles you get JSON via `logstash-logback-encoder`.

### Prometheus metrics

`GET /actuator/prometheus` exposes:

| Metric                         | Tags                                                       |
|--------------------------------|------------------------------------------------------------|
| `orchestrix_requests_total`    | tenant, provider, model, status, cache, fallback           |
| `orchestrix_llm_latency`       | tenant, provider, model — p50/p95/p99                     |
| `orchestrix_tokens_total`      | tenant, provider, model                                    |
| `orchestrix_cost_usd_total`    | tenant, provider, model                                    |
| `orchestrix_cache_hits_total`  | tenant                                                     |
| `orchestrix_fallback_total`    | tenant, from, to                                           |
| `orchestrix_routing_score`     | tenant, tier                                              |

---

## Enabling real providers

Set environment variables and restart. Each provider works independently —
you can enable only OpenAI and let Ollama/Anthropic stay in mock-fallback.

```bash
# OpenAI
export OPENAI_API_KEY=sk-...

# Anthropic
export ANTHROPIC_API_KEY=sk-ant-...
export ANTHROPIC_ENABLED=true

# Ollama (run server locally)
ollama serve            # in another terminal
ollama pull llama3.2

# Optionally set OLLAMA_BASE_URL if not localhost:11434
export OLLAMA_BASE_URL=http://localhost:11434

mvn spring-boot:run
```

Verify with:

```bash
curl -s http://localhost:8080/admin/provider/status \
  -H "Authorization: Bearer orx_acme_demo_key_123" | jq
```

You should see `configured: true` and `realCallPossible: true` for the
providers you set up.

### Mode + provider matrix

| Mode       | Provider has key/server | Provider does not       |
|------------|--------------------------|-------------------------|
| **MOCK**   | mock                     | mock                    |
| **REAL**   | real call                | mock with reason        |
| **HYBRID** | real call (mock on fail) | mock with reason        |

---

## Testing scenarios

Suite: `mvn test`

What's covered today:

| Test                                 | What it verifies                                                       |
|--------------------------------------|------------------------------------------------------------------------|
| `FeatureExtractorTest`               | Code / architecture / debugging keyword detection.                    |
| `RoutingEngineTest`                  | Tier selection, tenant ceiling, budget override, allow-list filter.   |
| `RateLimitServiceTest`               | Per-tenant capacity, tenant isolation under noisy neighbor.           |
| `ChatCompletionFallbackTest`         | Primary fails N times → secondary serves; all-fail propagates error.  |
| `ProviderExecutorTest`               | MOCK never calls real; REAL mocks when unconfigured; HYBRID mocks on failure. |
| `ChatApiIntegrationTest`             | End-to-end auth + chat + tenant isolation.                            |
| `ModeSwitchIntegrationTest`          | `/admin/mode/switch` flips behavior; provider status endpoint.        |

### Postman-style scenario walkthrough

The repo's behavior maps to these four standard demo scenarios:

```
Scenario A — MOCK mode
  POST /admin/mode/switch  body={"mode":"MOCK"}
  POST /v1/chat/completions
       → response starts "MOCK: ..."  cost=0  mode="MOCK"

Scenario B — REAL mode without OpenAI key
  POST /admin/mode/switch  body={"mode":"REAL"}
  (No OPENAI_API_KEY set)
  POST /v1/chat/completions  (prompt that scores MID/HIGH → routes to OPENAI)
       → response starts "MOCK: ..."  reason="credentials missing"

Scenario C — HYBRID mode without Ollama running
  POST /admin/mode/switch  body={"mode":"HYBRID"}
  (Ollama not running)
  POST /v1/chat/completions  (short prompt → LOW → OLLAMA)
       → response starts "MOCK: ..."  reason="real call failed: ConnectionRefused"

Scenario D — Tier routing
  Short prompt   ("hi")                                         → LOW  → OLLAMA
  Code+arch prompt ("Design a kafka system. ```code```")        → MID  → OPENAI
  Heavy prompt (long arch + code + debug + 1500 chars)          → HIGH → OPENAI/Anthropic
```

---

## Configuration cheat sheet

All env vars are optional in `dev` profile.

| Env var                     | Purpose                                          |
|-----------------------------|--------------------------------------------------|
| `SPRING_PROFILES_ACTIVE`    | `dev` (H2) or empty (MySQL)                     |
| `ORCHESTRIX_MODE`           | Default mode at boot: `MOCK` / `REAL` / `HYBRID`|
| `ORCHESTRIX_DB_URL`         | JDBC URL for MySQL                              |
| `ORCHESTRIX_DB_USER`        | DB user                                         |
| `ORCHESTRIX_DB_PASSWORD`    | DB password                                     |
| `ORCHESTRIX_REDIS_HOST`     | Redis host (only used if cache is on)           |
| `ORCHESTRIX_REDIS_PORT`     | Redis port                                      |
| `ORCHESTRIX_CACHE_ENABLED`  | Toggle cache layer                              |
| `ORCHESTRIX_REDIS_ENABLED`  | Toggle Redis backing for cache                  |
| `OPENAI_API_KEY`            | OpenAI key (else REAL mocks for this provider)  |
| `OPENAI_BASE_URL`           | Override OpenAI base URL                        |
| `OLLAMA_BASE_URL`           | Override Ollama base URL                        |
| `ANTHROPIC_API_KEY`         | Anthropic key                                   |
| `ANTHROPIC_ENABLED`         | Enable Anthropic adapter                        |

Deeper tuning (weights, timeouts, costs) lives in `application.yml`.

---

## Troubleshooting

- **`401 invalid api key`** — Use the demo keys above (or seed your own
  tenant). Header must be `Authorization: Bearer <raw key>`.
- **Always getting "MOCK: ..." back** — You're in HYBRID/REAL mode but the
  provider isn't configured (no API key) or the upstream is unreachable.
  Check `/admin/provider/status` — `configured` and `realCallPossible` tell
  you why.
- **`429 rate limit exceeded`** — Tenant bucket exhausted; wait or check
  `/tenant/state` for current bucket size.
- **`402 daily budget exhausted`** — Tenant hit daily cap. Increase
  `daily_budget_usd` for the tenant, or wait until UTC midnight.
- **Streaming returns `event: error`** — Check `/admin/providers/health`; the
  primary provider's circuit may be OPEN.
- **No metrics in Prometheus** — Hit `/actuator/prometheus`, not
  `/actuator/metrics`.
- **Mode switch doesn't seem to take effect** — Cached responses survive a
  switch but only for *real* responses (mocks aren't cached). If you got a
  cached real result, it's served regardless of the new mode.

---

## Where to read next

- [`DESIGN.md`](./DESIGN.md) — architecture, scoring formula, failure modes,
  tradeoffs, what was *not* built, and the scaling plan from
  10 → 1k → 100k RPS.
- [`SYSTEM_DEEP_DIVE.md`](./SYSTEM_DEEP_DIVE.md) — class-by-class explanation,
  full request lifecycle, sample logs, and routing-formula walkthroughs.
