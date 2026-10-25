-- User accounts (SDD V06). The e-mail is stored normalized (trimmed, lower-cased) and is unique,
-- so two values that differ only in case or surrounding whitespace map to one account
-- (REQ-FUNC-004). Only the Argon2id hash of the password is stored (REQ-SEC-003).

CREATE TABLE user_account (
                              id                  uuid        PRIMARY KEY,
                              email_normalized    text        NOT NULL,
                              password_hash       text        NOT NULL,
                              email_verified_at   timestamptz,
                              registration_locale text        NOT NULL CHECK (registration_locale IN ('ES', 'EN')),
                              created_at          timestamptz NOT NULL,
                              CONSTRAINT user_account_email_normalized_key UNIQUE (email_normalized)
);