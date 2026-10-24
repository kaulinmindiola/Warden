#!/usr/bin/env bash
# Creates the owner/application role split that makes Row Level Security effective
# (ADR-013, SDD V06). The official postgres image runs this script once, on the first
# start of an empty data directory, as the superuser defined by POSTGRES_USER.
#
# warden_owner owns the database and every schema object; Flyway migrates with it.
# warden_app is the runtime role: it owns nothing, so RLS applies to it, and it only
# receives DML on tables created by warden_owner.
set -Eeo pipefail

: "${WARDEN_DB_OWNER_PASSWORD:?WARDEN_DB_OWNER_PASSWORD must be set}"
: "${WARDEN_DB_APP_PASSWORD:?WARDEN_DB_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v owner_password="$WARDEN_DB_OWNER_PASSWORD" \
     -v app_password="$WARDEN_DB_APP_PASSWORD" \
     -v dbname="$POSTGRES_DB" <<'EOSQL'
CREATE ROLE warden_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
    PASSWORD :'owner_password';
CREATE ROLE warden_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
    PASSWORD :'app_password';

ALTER DATABASE :"dbname" OWNER TO warden_owner;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO warden_app;

ALTER DEFAULT PRIVILEGES FOR ROLE warden_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO warden_app;
ALTER DEFAULT PRIVILEGES FOR ROLE warden_owner IN SCHEMA public
    GRANT USAGE ON SEQUENCES TO warden_app;
EOSQL