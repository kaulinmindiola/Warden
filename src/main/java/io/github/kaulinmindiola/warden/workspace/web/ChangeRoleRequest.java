package io.github.kaulinmindiola.warden.workspace.web;

/**
 * Body of {@code PUT /api/v1/workspaces/{workspaceId}/members/{userId}/role}.
 *
 * @param role {@code VIEWER}, {@code MEMBER}, {@code ADMIN} or {@code OWNER}
 */
public record ChangeRoleRequest(String role) {}
