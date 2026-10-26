-- Workspace memberships (SDD V06, V07). Under Row Level Security, forced also for the table
-- owner: a row is visible only inside a transaction scoped to its workspace or to its user.
-- NULLIF turns the empty string left by a finished transaction-local setting into NULL, so an
-- unset context matches no row instead of failing the uuid cast.

CREATE TABLE workspace_member (
                                  workspace_id uuid        NOT NULL REFERENCES workspace (id),
                                  user_id      uuid        NOT NULL REFERENCES user_account (id),
                                  role         text        NOT NULL CHECK (role IN ('VIEWER', 'MEMBER', 'ADMIN', 'OWNER')),
                                  joined_at    timestamptz NOT NULL,
                                  PRIMARY KEY (workspace_id, user_id)
);

-- The workspaces of a user (GET /workspaces, MembershipLookup).
CREATE INDEX workspace_member_user_id_idx ON workspace_member (user_id);

ALTER TABLE workspace_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_member FORCE ROW LEVEL SECURITY;

-- Without WITH CHECK, the USING expression also applies to inserted and updated rows.
CREATE POLICY member_visibility ON workspace_member
    USING (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid
           OR user_id = NULLIF(current_setting('app.user_id', true), '')::uuid);