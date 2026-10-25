-- Transactional outbox (REQ-REL-001, SDD V06 and V10, ADR-008).
--
-- Domain services insert events in the same transaction as the state change they describe.
-- A dispatcher (phase 4) reads unpublished events with FOR UPDATE SKIP LOCKED, marks them
-- published and strips sealed secrets. No Row Level Security: only workers read this table.
-- Published events are purged after 7 days (REQ-FUNC-063).

CREATE TABLE outbox_event (
                              id             uuid        PRIMARY KEY,
                              type           text        NOT NULL,
                              schema_version integer     NOT NULL CHECK (schema_version >= 1),
                              workspace_id   uuid,
                              payload        jsonb       NOT NULL,
                              trace_id       text        NOT NULL,
                              occurred_at    timestamptz NOT NULL,
                              published_at   timestamptz
);

-- The dispatcher polls only pending events, oldest first.
CREATE INDEX outbox_event_pending_idx ON outbox_event (occurred_at) WHERE published_at IS NULL;