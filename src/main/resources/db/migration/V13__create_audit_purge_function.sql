-- Retention of the audit log (REQ-SEC-016, SDD V06): records older than 365 days are purged.
--
-- The application role has no DELETE privilege on audit_log (V2). This function, owned by
-- warden_owner and run with its privileges, is the only way to remove records. Whatever cutoff it
-- receives, it never deletes a record younger than 365 days by the database clock, so a
-- compromised application can only purge what the retention policy would purge anyway.
-- A NULL cutoff purges nothing (STRICT).

CREATE FUNCTION purge_audit_log(cutoff timestamptz)
    RETURNS bigint
    LANGUAGE sql
    STRICT
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS
$$
WITH purged AS (
    DELETE FROM public.audit_log
    WHERE occurred_at < LEAST(cutoff, now() - interval '365 days')
    RETURNING 1
)
SELECT count(*) FROM purged;
$$;

COMMENT ON FUNCTION purge_audit_log(timestamptz) IS
    'Deletes audit records older than the cutoff, never younger than 365 days (REQ-SEC-016)';

-- Functions are executable by PUBLIC by default; only the application role may purge.
REVOKE ALL ON FUNCTION purge_audit_log(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_audit_log(timestamptz) TO warden_app;