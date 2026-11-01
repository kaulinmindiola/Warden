package io.github.kaulinmindiola.warden.notification.application;

import java.io.Serial;

/** Only a DEAD delivery can be retried (REQ-FUNC-060). */
public class DeliveryNotRetryableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DeliveryNotRetryableException() {
        super("Only a dead delivery can be retried");
    }
}
