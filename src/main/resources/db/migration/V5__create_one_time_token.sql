-- Single-use tokens for e-mail verification and password reset (SDD V06, BR-006).
-- Only the SHA-256 hash of the 256-bit token is stored (REQ-SEC-004).

CREATE TABLE one_time_token (
                                id             uuid        PRIMARY KEY,
                                user_id        uuid        NOT NULL REFERENCES user_account (id),
                                purpose        text        NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
                                token_hash     bytea       NOT NULL CHECK (octet_length(token_hash) = 32),
                                expires_at     timestamptz NOT NULL,
                                consumed_at    timestamptz,
                                invalidated_at timestamptz,
                                CONSTRAINT one_time_token_token_hash_key UNIQUE (token_hash)
);

-- Finds the active tokens of a user and purpose, to invalidate them when a new one is issued.
CREATE INDEX one_time_token_active_idx ON one_time_token (user_id, purpose)
    WHERE consumed_at IS NULL AND invalidated_at IS NULL;