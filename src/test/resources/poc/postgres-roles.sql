-- POC-001 only: owner/application role split for the smoke test container.
-- Throwaway credentials for an ephemeral test container; not used anywhere else.
CREATE ROLE poc_owner LOGIN PASSWORD 'owner';
CREATE ROLE poc_app LOGIN PASSWORD 'app';

CREATE SCHEMA poc AUTHORIZATION poc_owner;
GRANT USAGE ON SCHEMA poc TO poc_app;

ALTER DEFAULT PRIVILEGES FOR ROLE poc_owner IN SCHEMA poc
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO poc_app;