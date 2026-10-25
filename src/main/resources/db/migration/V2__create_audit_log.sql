-- Append-only audit log (REQ-SEC-015, REQ-SEC-016, SDD V06 and V18).
--
-- The application role may read and insert, but never update, delete or truncate: the
-- database enforces the integrity of the log, not the application code. Records older than
-- 365 days are purged by a SECURITY DEFINER function added later (REQ-SEC-016).

CREATE TABLE audit_log (
                           id            uuid        PRIMARY KEY,
                           occurred_at   timestamptz NOT NULL,
                           actor_user_id uuid,
                           workspace_id  uuid,
                           action        text        NOT NULL,
                           outcome       text        NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE')),
                           details       jsonb       NOT NULL DEFAULT '{}'::jsonb,
                           trace_id      text        NOT NULL
);

COMMENT ON TABLE audit_log IS 'Append-only: warden_app has no UPDATE, DELETE or TRUNCATE privilege';

-- Retention purge scans by age.
CREATE INDEX audit_log_occurred_at_idx ON audit_log (occurred_at);

-- Per-workspace history, newest first.
CREATE INDEX audit_log_workspace_idx ON audit_log (workspace_id, occurred_at DESC)
    WHERE workspace_id IS NOT NULL;

-- Default privileges grant DML on every table created by warden_owner; take back the
-- privileges that would let the application alter the log.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM warden_app;