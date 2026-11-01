-- Notification deliveries (REQ-FUNC-047, 061, SDD V06, V13). The notification handler creates one
-- row per event, recipient and channel, inside the outbox dispatch transaction; the unique key
-- absorbs a re-dispatch after a crash (REQ-FUNC-061). The template version is pinned at creation
-- and the content is rendered at send time. Link tokens are kept sealed (AES-256-GCM ciphertext
-- and the id of its key) until the delivery is sent, cancelled or expires (REQ-SEC-005).
--
-- Row Level Security is forced: the workers see every row in the system context; a workspace
-- context sees only the rows of its workspace. Deliveries are kept, so the application role may
-- not delete them.

CREATE TABLE notification_delivery (
                                       id                uuid        PRIMARY KEY,
                                       event_id          uuid        NOT NULL,
                                       recipient_key     text        NOT NULL,
                                       channel           text        NOT NULL CHECK (channel IN ('EMAIL')),
                                       scope             text        NOT NULL CHECK (scope IN ('SYSTEM', 'WORKSPACE')),
                                       workspace_id      uuid,
                                       invitation_id     uuid,
                                       template_key      text        NOT NULL,
                                       locale            text        NOT NULL CHECK (locale IN ('es', 'en')),
                                       template_version  integer     NOT NULL CHECK (template_version >= 1),
                                       recipient_email   text        NOT NULL,
                                       variables         jsonb       NOT NULL,
                                       secret_ciphertext bytea,
                                       secret_key_id     text,
                                       secret_expires_at timestamptz,
                                       status            text        NOT NULL
                                           CHECK (status IN ('PENDING', 'RETRYING', 'SENT', 'DEAD', 'CANCELLED')),
                                       attempts          integer     NOT NULL CHECK (attempts >= 0),
                                       next_attempt_at   timestamptz NOT NULL,
                                       lease_until       timestamptz,
                                       provider_used     text,
                                       last_error_class  text,
                                       origin_trace_id   text        NOT NULL,
                                       created_at        timestamptz NOT NULL,
                                       sent_at           timestamptz,
                                       CHECK ((scope = 'SYSTEM' AND workspace_id IS NULL)
                                           OR (scope = 'WORKSPACE' AND workspace_id IS NOT NULL)),
                                       CHECK ((secret_ciphertext IS NULL) = (secret_key_id IS NULL)),
                                       UNIQUE (event_id, recipient_key, channel)
);

-- Deliveries due for an attempt, for the delivery worker.
CREATE INDEX notification_delivery_due_idx ON notification_delivery (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRYING');

-- Deliveries of a workspace by status, for the delivery listing.
CREATE INDEX notification_delivery_workspace_status_idx ON notification_delivery (workspace_id, status);

-- Deliveries of an invitation, for cancellations when it is revoked or replaced.
CREATE INDEX notification_delivery_invitation_idx ON notification_delivery (invitation_id)
    WHERE invitation_id IS NOT NULL;

REVOKE DELETE ON notification_delivery FROM warden_app;

ALTER TABLE notification_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_delivery FORCE ROW LEVEL SECURITY;

CREATE POLICY notification_delivery_visibility ON notification_delivery
    USING      (current_setting('app.system_context', true) = 'on'
                OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid)
    WITH CHECK (current_setting('app.system_context', true) = 'on'
                OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);