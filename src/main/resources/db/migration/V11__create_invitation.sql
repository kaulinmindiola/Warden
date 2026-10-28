-- Invitations to a workspace (SDD V06, V15, V16). Only the SHA-256 of the single-use token is
-- stored (REQ-SEC-004). OWNER cannot be granted by invitation (BR-008). At most one PENDING
-- invitation per workspace and e-mail; EXPIRED is derived from expires_at and never stored, so an
-- expired invitation keeps its PENDING slot until it is replaced. Row Level Security is forced:
-- rows are visible and writable only in a transaction scoped to their workspace. Accepting an
-- invitation without that context gets its own policy with the acceptance use case.

CREATE TABLE invitation (
                            id               uuid        PRIMARY KEY,
                            workspace_id     uuid        NOT NULL REFERENCES workspace (id),
                            email_normalized text        NOT NULL,
                            role             text        NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER')),
                            locale           text        NOT NULL CHECK (locale IN ('es', 'en')),
                            token_hash       bytea       NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
                            status           text        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'REPLACED')),
                            invited_by       uuid        NOT NULL REFERENCES user_account (id),
                            expires_at       timestamptz NOT NULL,
                            created_at       timestamptz NOT NULL
);

-- One pending invitation per workspace and e-mail (REQ-FUNC-032).
CREATE UNIQUE INDEX invitation_pending_email_key ON invitation (workspace_id, email_normalized)
    WHERE status = 'PENDING';

-- Pending invitations of a workspace, newest first (REQ-FUNC-034).
CREATE INDEX invitation_workspace_created_idx ON invitation (workspace_id, created_at DESC, id DESC);

ALTER TABLE invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation FORCE ROW LEVEL SECURITY;

CREATE POLICY invitation_tenant ON invitation
    USING      (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);