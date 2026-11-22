-- Schema fixture for disposable integration-test databases.
CREATE TABLE IF NOT EXISTS account_deletion (
    token_hash TEXT PRIMARY KEY,
    owner_id UUID UNIQUE,
    bucket TEXT,
    object_key TEXT,
    email TEXT UNIQUE,
    email_deadline TIMESTAMP WITH TIME ZONE,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease UUID,
    lease_until TIMESTAMP WITH TIME ZONE
);
