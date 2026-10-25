package io.github.kaulinmindiola.warden.shared.crypto;

import java.io.Serial;

/** A sealed secret cannot be opened. The message never contains the secret or the key. */
public class SecretCipherException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why the secret cannot be opened. */
    public enum Reason {
        /** The key id is not in the key ring, for example after the key was retired. */
        UNKNOWN_KEY,
        /** The expiry instant has been reached. */
        EXPIRED,
        /** The ciphertext, key id or expiry was altered, or the ciphertext is malformed. */
        TAMPERED
    }

    private final Reason reason;

    public SecretCipherException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
