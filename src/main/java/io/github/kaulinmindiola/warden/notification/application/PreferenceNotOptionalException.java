package io.github.kaulinmindiola.warden.notification.application;

import java.io.Serial;

/** An attempt to turn off a SECURITY or TRANSACTIONAL notification (BR-003); nothing was changed. */
public class PreferenceNotOptionalException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PreferenceNotOptionalException(String key) {
        super("Notification " + key + " cannot be disabled");
    }
}
