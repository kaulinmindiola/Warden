package io.github.kaulinmindiola.warden.notification.application;

import java.io.Serial;

/** The delivery does not exist in the workspace of the request. */
public class DeliveryNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DeliveryNotFoundException() {
        super("The delivery does not exist in this workspace");
    }
}
