-- Orchestrix initial schema
-- Designed for MySQL 8 (compatible with H2 in MySQL mode for tests).

CREATE TABLE IF NOT EXISTS tenants (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    api_key_hash        VARCHAR(128) NOT NULL,
    daily_budget_usd    DECIMAL(12,4) NOT NULL DEFAULT 10.0000,
    monthly_budget_usd  DECIMAL(14,4) NOT NULL DEFAULT 200.0000,
    rate_limit_rpm      INT NOT NULL DEFAULT 60,
    rate_limit_rps      INT NOT NULL DEFAULT 5,
    allowed_providers   VARCHAR(255) NOT NULL DEFAULT 'OPENAI,OLLAMA,ANTHROPIC',
    max_model_tier      VARCHAR(16)  NOT NULL DEFAULT 'HIGH',
    enabled             TINYINT(1)   NOT NULL DEFAULT 1,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenants_tenant_id UNIQUE (tenant_id),
    CONSTRAINT uk_tenants_api_key_hash UNIQUE (api_key_hash)
);

CREATE TABLE IF NOT EXISTS request_logs (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(64)  NOT NULL,
    tenant_id           VARCHAR(64)  NOT NULL,
    provider            VARCHAR(32)  NOT NULL,
    model               VARCHAR(64)  NOT NULL,
    routing_score       DOUBLE       NULL,
    complexity_score    DOUBLE       NULL,
    cost_score          DOUBLE       NULL,
    reliability_score   DOUBLE       NULL,
    prompt_tokens       INT          NULL,
    completion_tokens   INT          NULL,
    total_tokens        INT          NULL,
    cost_usd            DECIMAL(12,6) NULL,
    latency_ms          BIGINT       NULL,
    status              VARCHAR(24)  NOT NULL,
    fallback_used       TINYINT(1)   NOT NULL DEFAULT 0,
    fallback_chain      VARCHAR(255) NULL,
    error_message       VARCHAR(512) NULL,
    streamed            TINYINT(1)   NOT NULL DEFAULT 0,
    cache_hit           TINYINT(1)   NOT NULL DEFAULT 0,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_request_logs_request_id UNIQUE (request_id)
);

CREATE INDEX idx_request_logs_tenant_created ON request_logs (tenant_id, created_at);
CREATE INDEX idx_request_logs_provider ON request_logs (provider, created_at);

CREATE TABLE IF NOT EXISTS provider_health (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider            VARCHAR(32)  NOT NULL,
    health_score        DOUBLE       NOT NULL DEFAULT 1.0,
    failure_rate        DOUBLE       NOT NULL DEFAULT 0.0,
    avg_latency_ms      DOUBLE       NOT NULL DEFAULT 0.0,
    circuit_state       VARCHAR(24)  NOT NULL DEFAULT 'CLOSED',
    consecutive_failures INT         NOT NULL DEFAULT 0,
    last_success_at     TIMESTAMP    NULL,
    last_failure_at     TIMESTAMP    NULL,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_provider_health_provider UNIQUE (provider)
);

CREATE TABLE IF NOT EXISTS tenant_usage (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    usage_date          DATE         NOT NULL,
    request_count       BIGINT       NOT NULL DEFAULT 0,
    cost_usd            DECIMAL(14,6) NOT NULL DEFAULT 0,
    total_tokens        BIGINT       NOT NULL DEFAULT 0,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenant_usage_day UNIQUE (tenant_id, usage_date)
);

-- Seed providers so health checks render even with no traffic.
INSERT INTO provider_health (provider, health_score, circuit_state)
VALUES ('OPENAI', 1.0, 'CLOSED'),
       ('ANTHROPIC', 1.0, 'CLOSED'),
       ('OLLAMA', 1.0, 'CLOSED');

-- Demo tenants are seeded by TenantBootstrap on startup so the SHA-256 hashes
-- match real raw keys. See TenantBootstrap for the demo keys.
