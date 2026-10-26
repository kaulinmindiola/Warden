-- Workspaces (SDD V06). Not under Row Level Security: access is gated by membership (V07 layer
-- L1). The name is not unique; a workspace is identified by its id (REQ-FUNC-027). Deleting a
-- workspace sets deleted_at; a deleted workspace behaves as non-existent (BR-012).

CREATE TABLE workspace (
                           id         uuid        PRIMARY KEY,
                           name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
                           created_by uuid        NOT NULL REFERENCES user_account (id),
                           created_at timestamptz NOT NULL,
                           deleted_at timestamptz
);