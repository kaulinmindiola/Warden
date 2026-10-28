package io.github.kaulinmindiola.warden.workspace;

import java.util.Map;

/**
 * {@code workspace.WorkspaceDeleted} (SDD V10). Its payload is empty: consumers use the {@code
 * workspaceId} of the envelope to cancel every unsent delivery of the workspace (REQ-FUNC-030).
 */
public final class WorkspaceDeleted {

    /** Event type stored in the outbox. */
    public static final String TYPE = "workspace.WorkspaceDeleted";

    /** The payload, serialized as {@code {}}. */
    public static final Map<String, Object> PAYLOAD = Map.of();

    private WorkspaceDeleted() {}
}
