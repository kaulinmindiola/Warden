package io.github.kaulinmindiola.warden.workspace.web;

/**
 * Body of {@code POST /api/v1/workspaces}. Validation happens in the service.
 *
 * @param name workspace name, 1 to 100 characters
 */
public record CreateWorkspaceRequest(String name) {}
