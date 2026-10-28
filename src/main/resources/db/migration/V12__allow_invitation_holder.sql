-- Accepting an invitation (UC-14, SDD V07). The caller is not a member yet, so invitation_tenant
-- shows nothing. Instead the transaction carries the SHA-256 of the presented token, hex-encoded,
-- in app.invitation_token_hash: that row, and only that row, becomes readable, and it may only be
-- updated to ACCEPTED. Knowing the hash requires knowing the token, which is exactly what the
-- invitation grants. Inserting stays reserved to the workspace context.

CREATE POLICY invitation_holder_read ON invitation
    FOR SELECT
                   USING (token_hash = decode(NULLIF(current_setting('app.invitation_token_hash', true), ''), 'hex'));

CREATE POLICY invitation_holder_accept ON invitation
    FOR UPDATE
                   USING      (token_hash = decode(NULLIF(current_setting('app.invitation_token_hash', true), ''), 'hex'))
        WITH CHECK (token_hash = decode(NULLIF(current_setting('app.invitation_token_hash', true), ''), 'hex')
                   AND status = 'ACCEPTED');

-- The application only ever changes the status of an invitation and never deletes one: take back
-- the default table privileges so not even the token holder can change its role.
REVOKE UPDATE, DELETE, TRUNCATE ON invitation FROM warden_app;
GRANT UPDATE (status) ON invitation TO warden_app;