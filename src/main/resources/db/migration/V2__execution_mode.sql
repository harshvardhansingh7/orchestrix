-- Track which mode resolved each request and whether the response was real or mock.
-- Useful for cost reconciliation, demo audits, and "did the mode flip catch us out?" questions.
-- Use one ALTER per column so this works on H2 (test) as well as MySQL.

ALTER TABLE request_logs ADD COLUMN mode VARCHAR(16) NULL;
ALTER TABLE request_logs ADD COLUMN execution_type VARCHAR(16) NULL;
ALTER TABLE request_logs ADD COLUMN execution_reason VARCHAR(255) NULL;

CREATE INDEX idx_request_logs_execution_type ON request_logs (execution_type);
