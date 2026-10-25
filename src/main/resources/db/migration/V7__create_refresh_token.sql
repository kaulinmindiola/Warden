-- Refresh tokens (SDD V06). Only the SHA-256 hash of the 256-bit token is stored (REQ-SEC-004).
-- A token is used at most once; it expires after 7 days or at the session's absolute expiry,
-- whichever comes first (DEC-009).

CREATE TABLE refresh_token (
                               id         uuid        PRIMARY KEY,
                               session_id uuid        NOT NULL REFERENCES auth_session (id),
                               token_hash bytea       NOT NULL CHECK (octet_length(token_hash) = 32),
                               issued_at  timestamptz NOT NULL,
                               expires_at timestamptz NOT NULL,
                               used_at    timestamptz,
                               CONSTRAINT refresh_token_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX refresh_token_session_idx ON refresh_token (session_id);