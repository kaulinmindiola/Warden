package io.github.kaulinmindiola.warden.shared.security;

import java.io.Serial;

/**
 * Memberships cannot be resolved, for example because the database is unavailable. Requests that
 * need them are denied with 503 (fail closed, REQ-SEC-011).
 */
public class MembershipUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MembershipUnavailableException(String message) {
        super(message);
    }

    public MembershipUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
