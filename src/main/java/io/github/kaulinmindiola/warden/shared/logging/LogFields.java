package io.github.kaulinmindiola.warden.shared.logging;

/**
 * Names of the MDC fields that correlate log lines (SDD V19). Structured logging writes every MDC
 * entry as a field of the JSON line, next to {@code traceId} and {@code spanId}, which Micrometer
 * Tracing puts there itself.
 */
public final class LogFields {

    /** The authenticated user of the request (REQ-OBS-004). */
    public static final String USER_ID = "userId";

    /** The workspace named in the path of the request (REQ-OBS-004). */
    public static final String WORKSPACE_ID = "workspaceId";

    /**
     * The trace of the request that produced the event or delivery a worker is processing
     * (REQ-OBS-006): a worker runs outside that request, so its own {@code traceId} differs.
     */
    public static final String ORIGIN_TRACE_ID = "originTraceId";

    private LogFields() {}
}
