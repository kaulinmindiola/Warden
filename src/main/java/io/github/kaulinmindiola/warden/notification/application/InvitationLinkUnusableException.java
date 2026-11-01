package io.github.kaulinmindiola.warden.notification.application;

import java.io.Serial;

/**
 * The delivery belongs to an invitation that is no longer pending: revoked, replaced, accepted or
 * expired (REQ-FUNC-060).
 */
public class InvitationLinkUnusableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvitationLinkUnusableException() {
        super("The invitation of this delivery is no longer pending");
    }
}
