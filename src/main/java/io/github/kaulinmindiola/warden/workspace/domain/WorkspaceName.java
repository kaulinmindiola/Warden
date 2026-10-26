package io.github.kaulinmindiola.warden.workspace.domain;

import java.util.Objects;

/**
 * Name of a workspace: 1 to 100 characters, counted as Unicode code points like PostgreSQL's
 * {@code char_length}, after removing leading and trailing white space (REQ-FUNC-027).
 *
 * @param value the stripped name
 */
public record WorkspaceName(String value) {

    public static final int MAX_LENGTH = 100;

    /**
     * @throws InvalidWorkspaceNameException if the value is empty or longer than {@value
     *     #MAX_LENGTH} characters
     */
    public WorkspaceName {
        Objects.requireNonNull(value, "value");
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > MAX_LENGTH) {
            throw new InvalidWorkspaceNameException();
        }
    }

    /** The name as submitted, stripped of leading and trailing white space. */
    public static WorkspaceName of(String raw) {
        Objects.requireNonNull(raw, "raw");
        return new WorkspaceName(raw.strip());
    }
}
