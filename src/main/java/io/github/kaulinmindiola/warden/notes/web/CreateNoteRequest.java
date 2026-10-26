package io.github.kaulinmindiola.warden.notes.web;

/**
 * Body of {@code POST /api/v1/workspaces/{workspaceId}/notes}. Validated by the service.
 *
 * @param title 1 to 200 characters
 * @param body up to 10 000 characters
 */
public record CreateNoteRequest(String title, String body) {}
