-- Persist the routing explanation and response-quality signals on every
-- request log row. Lets historical queries answer "why was this provider
-- picked?" and "what did our quality evaluator think of yesterday's run?".
-- One ALTER per column for H2 compatibility.

ALTER TABLE request_logs ADD COLUMN routing_reasoning VARCHAR(2048) NULL;
ALTER TABLE request_logs ADD COLUMN routing_explanation_json TEXT NULL;
ALTER TABLE request_logs ADD COLUMN quality_score DOUBLE NULL;
ALTER TABLE request_logs ADD COLUMN quality_note VARCHAR(255) NULL;

CREATE INDEX idx_request_logs_quality_score ON request_logs (quality_score);
