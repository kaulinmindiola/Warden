-- Notification templates (REQ-FUNC-052, REQ-SEC-013, SDD V06, V14-A6).
--
-- Release 1.0 ships the global templates only: the seven keys of SRS appendix B (the new-device
-- notice is deferred to 1.1), in es and en, version 1, plain text (DEC-040). They are read-only:
-- the application role may only read them. The columns and the scope check already admit the
-- workspace customizations of release 1.1 (REQ-FUNC-053, 054), which will be insert-only.
--
-- Rows are inserted before Row Level Security is forced, since the policy would otherwise apply
-- to these inserts too. Global rows are visible to everyone; workspace rows only in their
-- workspace's context or in the system context of the notification workers (SDD V07).

CREATE TABLE notification_template (
                                       id           uuid        PRIMARY KEY,
                                       scope        text        NOT NULL CHECK (scope IN ('GLOBAL', 'WORKSPACE')),
                                       workspace_id uuid,
                                       template_key text        NOT NULL,
                                       locale       text        NOT NULL CHECK (locale IN ('es', 'en')),
                                       version      integer     NOT NULL CHECK (version >= 1),
                                       subject      text        NOT NULL CHECK (char_length(subject) BETWEEN 1 AND 200),
                                       body         text        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 20000),
                                       created_by   uuid,
                                       created_at   timestamptz NOT NULL DEFAULT now(),
                                       CHECK ((scope = 'GLOBAL' AND workspace_id IS NULL)
                                           OR (scope = 'WORKSPACE' AND workspace_id IS NOT NULL)),
                                       UNIQUE NULLS NOT DISTINCT (scope, workspace_id, template_key, locale, version)
);

INSERT INTO notification_template (id, scope, template_key, locale, version, subject, body)
VALUES
    (gen_random_uuid(), 'GLOBAL', 'security.refresh-reuse', 'es', 1,
     'Alerta de seguridad: cerramos una sesión de tu cuenta',
     E'Hola:\n\nEl {{occurredAt}} detectamos que se reutilizó un token de sesión de tu cuenta de Warden. Por precaución, cerramos esa sesión.\n\nSi no reconoces esta actividad, cambia tu contraseña.\n\n— Warden'),
    (gen_random_uuid(), 'GLOBAL', 'security.refresh-reuse', 'en', 1,
     'Security alert: we closed a session of your account',
     E'Hello,\n\nOn {{occurredAt}} we detected that a session token of your Warden account was reused. As a precaution, we closed that session.\n\nIf you do not recognize this activity, change your password.\n\n— Warden'),

    (gen_random_uuid(), 'GLOBAL', 'account.password-reset', 'es', 1,
     'Restablece tu contraseña de Warden',
     E'Hola:\n\nRecibimos una solicitud para restablecer la contraseña de tu cuenta. Usa este enlace antes del {{expiresAt}}:\n\n{{resetLink}}\n\nSi no la solicitaste, ignora este mensaje: tu contraseña no cambiará.\n\n— Warden'),
    (gen_random_uuid(), 'GLOBAL', 'account.password-reset', 'en', 1,
     'Reset your Warden password',
     E'Hello,\n\nWe received a request to reset the password of your account. Use this link before {{expiresAt}}:\n\n{{resetLink}}\n\nIf you did not request it, ignore this message: your password will not change.\n\n— Warden'),

    (gen_random_uuid(), 'GLOBAL', 'account.email-verification', 'es', 1,
     'Verifica tu email en Warden',
     E'Hola:\n\nConfirma que este email es tuyo con el siguiente enlace antes del {{expiresAt}}:\n\n{{verificationLink}}\n\nSi no creaste una cuenta en Warden, ignora este mensaje.\n\n— Warden'),
    (gen_random_uuid(), 'GLOBAL', 'account.email-verification', 'en', 1,
     'Verify your email on Warden',
     E'Hello,\n\nConfirm that this email is yours with the following link before {{expiresAt}}:\n\n{{verificationLink}}\n\nIf you did not create a Warden account, ignore this message.\n\n— Warden'),

    (gen_random_uuid(), 'GLOBAL', 'account.registration-attempt', 'es', 1,
     'Intento de registro con tu email',
     E'Hola:\n\nEl {{occurredAt}} alguien intentó crear una cuenta de Warden con este email, que ya tiene una. No se creó ninguna cuenta nueva.\n\nSi fuiste tú, inicia sesión o restablece tu contraseña. Si no, puedes ignorar este mensaje.\n\n— Warden'),
    (gen_random_uuid(), 'GLOBAL', 'account.registration-attempt', 'en', 1,
     'Registration attempt with your email',
     E'Hello,\n\nOn {{occurredAt}} someone tried to create a Warden account with this email, which already has one. No new account was created.\n\nIf it was you, sign in or reset your password. Otherwise, you can ignore this message.\n\n— Warden'),

    (gen_random_uuid(), 'GLOBAL', 'account.password-changed', 'es', 1,
     'Tu contraseña de Warden cambió',
     E'Hola:\n\nLa contraseña de tu cuenta se cambió el {{occurredAt}}.\n\nSi no fuiste tú, restablece tu contraseña de inmediato.\n\n— Warden'),
    (gen_random_uuid(), 'GLOBAL', 'account.password-changed', 'en', 1,
     'Your Warden password changed',
     E'Hello,\n\nThe password of your account was changed on {{occurredAt}}.\n\nIf it was not you, reset your password immediately.\n\n— Warden'),

    (gen_random_uuid(), 'GLOBAL', 'workspace.invitation', 'es', 1,
     'Te invitaron a {{workspaceName}} en Warden',
     E'Hola:\n\nTe invitaron a unirte al workspace {{workspaceName}} con el rol {{role}}. Acepta la invitación antes del {{expiresAt}}:\n\n{{invitationLink}}\n\nSi no esperabas esta invitación, ignora este mensaje.\n\n— Warden'),
    (gen_random_uuid(), 'GLOBAL', 'workspace.invitation', 'en', 1,
     'You are invited to {{workspaceName}} on Warden',
     E'Hello,\n\nYou are invited to join the workspace {{workspaceName}} with the role {{role}}. Accept the invitation before {{expiresAt}}:\n\n{{invitationLink}}\n\nIf you did not expect this invitation, ignore this message.\n\n— Warden'),

    (gen_random_uuid(), 'GLOBAL', 'workspace.role-changed', 'es', 1,
     'Cambio de rol en {{workspaceName}}',
     E'Hola:\n\nEl rol de {{affectedMember}} en el workspace {{workspaceName}} cambió de {{previousRole}} a {{newRole}}.\n\n— Warden'),
    (gen_random_uuid(), 'GLOBAL', 'workspace.role-changed', 'en', 1,
     'Role change in {{workspaceName}}',
     E'Hello,\n\nThe role of {{affectedMember}} in the workspace {{workspaceName}} changed from {{previousRole}} to {{newRole}}.\n\n— Warden');

-- Read-only for the application in release 1.0 (default privileges grant full DML).
REVOKE INSERT, UPDATE, DELETE ON notification_template FROM warden_app;

ALTER TABLE notification_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_template FORCE ROW LEVEL SECURITY;

CREATE POLICY notification_template_visibility ON notification_template
    USING (scope = 'GLOBAL'
        OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid
        OR current_setting('app.system_context', true) = 'on');