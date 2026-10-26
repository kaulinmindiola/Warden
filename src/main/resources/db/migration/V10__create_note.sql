-- Notes, the reference workspace-scoped resource (SDD V06, V07). Row Level Security is forced
-- also for the table owner, and the policy checks both the rows read and the rows written, so a
-- note can only be read or written inside a transaction scoped to its workspace. version backs
-- optimistic locking (REQ-FUNC-045).

CREATE TABLE note (
                      id           uuid        PRIMARY KEY,
                      workspace_id uuid        NOT NULL REFERENCES workspace (id),
                      title        text        NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
                      body         text        NOT NULL CHECK (char_length(body) <= 10000),
                      author_id    uuid        NOT NULL REFERENCES user_account (id),
                      version      bigint      NOT NULL DEFAULT 0,
                      created_at   timestamptz NOT NULL,
                      updated_at   timestamptz NOT NULL
);

-- Notes of a workspace, newest first.
CREATE INDEX note_workspace_created_idx ON note (workspace_id, created_at DESC, id DESC);

ALTER TABLE note ENABLE ROW LEVEL SECURITY;
ALTER TABLE note FORCE ROW LEVEL SECURITY;

CREATE POLICY note_tenant ON note
    USING      (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);