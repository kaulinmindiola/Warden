-- Login sessions (SDD V06, DEC-009). A session is usable while it is not revoked and before its
-- absolute expiry; once revoked it never becomes active again.

CREATE TABLE auth_session (
                              id                  uuid        PRIMARY KEY,
                              user_id             uuid        NOT NULL REFERENCES user_account (id),
                              created_at          timestamptz NOT NULL,
                              last_used_at        timestamptz NOT NULL,
                              absolute_expires_at timestamptz NOT NULL,
                              user_agent_summary  text,
                              revoked_at          timestamptz,
                              revoke_reason       text        CHECK (revoke_reason IN ('LOGOUT', 'TERMINATED', 'REUSE', 'PASSWORD_RESET')),
                              CONSTRAINT auth_session_revocation_check CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);

-- Active sessions of a user (session listing, revoke-all on password reset).
CREATE INDEX auth_session_active_user_idx ON auth_session (user_id) WHERE revoked_at IS NULL;

-- Recently revoked sessions (revocation reconciliation, phase 3).
CREATE INDEX auth_session_revoked_at_idx ON auth_session (revoked_at) WHERE revoked_at IS NOT NULL;