package io.github.kaulinmindiola.warden.architecture.fixture.notification;

import io.github.kaulinmindiola.warden.architecture.fixture.shared.EventRecord;
import java.util.Locale;

/** Depends only on the shared kernel and the JDK: allowed by FF-02. */
public class CompliantHandler {

    public String handle(EventRecord event) {
        return event.type().toLowerCase(Locale.ROOT);
    }
}
