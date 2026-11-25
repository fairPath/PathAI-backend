-- Schema fixture for disposable integration-test databases.
CREATE TABLE IF NOT EXISTS account_deletion (
    token_hash TEXT PRIMARY KEY,
    owner_id UUID NOT NULL UNIQUE,
    bucket TEXT NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    notification_started BOOLEAN NOT NULL DEFAULT FALSE
);
