package io.github.kaulinmindiola.warden.notification.application;

import io.github.kaulinmindiola.warden.notification.domain.ProviderResult;
import java.util.Objects;

/**
 * The outcome of one delivery attempt across both providers (SDD V14-A5).
 *
 * @param result the result of the last provider called, or TRANSIENT_FAILURE if none could be
 * @param provider the name of the last provider called, or {@link #NONE} when both breakers were
 *     open
 */
public record SendOutcome(ProviderResult result, String provider) {

    /** Recorded as the provider when no provider could be called. */
    public static final String NONE = "NONE";

    public SendOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(provider, "provider");
    }
}
