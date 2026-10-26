package io.github.kaulinmindiola.warden.notes.web;

/**
 * Body of {@code PUT /api/v1/workspaces/{workspaceId}/notes/{noteId}}. Validated by the service.
 *
 * @param title 1 to 200 characters
 * @param body up to 10 000 characters
 * @param version the version being edited, as last read
 */
public record UpdateNoteRequest(String title, String body, Long version) {}
