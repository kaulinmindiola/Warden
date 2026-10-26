package io.github.kaulinmindiola.warden.workspace.application;

import java.io.Serial;

/** The user is not a member of the workspace of the request. */
public class MemberNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MemberNotFoundException() {
        super("The user is not a member of this workspace");
    }
}
