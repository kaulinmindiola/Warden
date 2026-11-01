-- Recipient language and notification preferences (REQ-FUNC-049 as reduced for release 1.0,
-- REQ-FUNC-050, 051, SDD V06). The notification module keeps no foreign key to other modules:
-- user and workspace ids are plain values taken from the events and the API.

-- The language a user chose in their preferences; without a row, the language carried by the
-- event applies, and es after that.
CREATE TABLE recipient_setting (
                                   user_id    uuid        PRIMARY KEY,
                                   locale     text        NOT NULL CHECK (locale IN ('es', 'en')),
                                   updated_at timestamptz NOT NULL
);

-- Opt-outs of INFORMATIONAL notifications per workspace; without a row, the notification is
-- enabled (REQ-FUNC-050). Release 1.0 offers it only for workspace.role-changed.
CREATE TABLE notification_preference (
                                         user_id      uuid        NOT NULL,
                                         template_key text        NOT NULL,
                                         workspace_id uuid        NOT NULL,
                                         enabled      boolean     NOT NULL,
                                         updated_at   timestamptz NOT NULL,
                                         PRIMARY KEY (user_id, template_key, workspace_id)
);