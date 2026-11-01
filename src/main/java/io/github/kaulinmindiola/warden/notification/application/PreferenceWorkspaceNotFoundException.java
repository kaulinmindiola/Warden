package io.github.kaulinmindiola.warden.notification.application;

import java.io.Serial;

/**
 * A preference for a workspace the user is not a member of, or that does not exist; answered like
 * any unknown resource (REQ-FUNC-023). Nothing was changed.
 */
public class PreferenceWorkspaceNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PreferenceWorkspaceNotFoundException() {
        super("The workspace of a preference is not accessible to the user");
    }
}
