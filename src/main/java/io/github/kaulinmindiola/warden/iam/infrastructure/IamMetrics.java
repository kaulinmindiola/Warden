package io.github.kaulinmindiola.warden.iam.infrastructure;

import io.github.kaulinmindiola.warden.iam.domain.AuthSession;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Identity metrics of SDD V19 (REQ-OBS-001), exposed in Prometheus format on the management port:
 *
 * <ul>
 *   <li>{@code warden_auth_logins_total{outcome=success|failure|rate_limited}};
 *   <li>{@code warden_auth_refresh_total{outcome=success|invalid|reuse}};
 *   <li>{@code warden_auth_sessions_revoked_total{reason=logout|terminate|reuse|password_reset}}.
 * </ul>
 *
 * <p>Every series is registered at startup, so each one is exposed from zero. Tags take only these
 * fixed values, never a user, session or address (REQ-OBS-003).
 */
@Component
public class IamMetrics {

    public static final String LOGINS = "warden.auth.logins";
    public static final String REFRESHES = "warden.auth.refresh";
    public static final String SESSIONS_REVOKED = "warden.auth.sessions.revoked";
    public static final String OUTCOME_TAG = "outcome";
    public static final String REASON_TAG = "reason";

    /** How a login ended. */
    public enum LoginOutcome {
        SUCCESS,
        FAILURE,
        RATE_LIMITED;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How a refresh ended; a refresh rejected by its rate limit is counted by the rate limiter. */
    public enum RefreshOutcome {
        SUCCESS,
        INVALID,
        REUSE;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Map<LoginOutcome, Counter> logins = new EnumMap<>(LoginOutcome.class);
    private final Map<RefreshOutcome, Counter> refreshes = new EnumMap<>(RefreshOutcome.class);
    private final Map<AuthSession.RevokeReason, Counter> revocations = new EnumMap<>(AuthSession.RevokeReason.class);

    public IamMetrics(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        for (LoginOutcome outcome : LoginOutcome.values()) {
            logins.put(
                    outcome,
                    Counter.builder(LOGINS)
                            .description("Login attempts by outcome")
                            .tag(OUTCOME_TAG, outcome.tag())
                            .register(meterRegistry));
        }
        for (RefreshOutcome outcome : RefreshOutcome.values()) {
            refreshes.put(
                    outcome,
                    Counter.builder(REFRESHES)
                            .description("Refresh requests by outcome")
                            .tag(OUTCOME_TAG, outcome.tag())
                            .register(meterRegistry));
        }
        for (AuthSession.RevokeReason reason : AuthSession.RevokeReason.values()) {
            revocations.put(
                    reason,
                    Counter.builder(SESSIONS_REVOKED)
                            .description("Sessions revoked by cause")
                            .tag(REASON_TAG, reasonTag(reason))
                            .register(meterRegistry));
        }
    }

    public void login(LoginOutcome outcome) {
        logins.get(outcome).increment();
    }

    public void refresh(RefreshOutcome outcome) {
        refreshes.get(outcome).increment();
    }

    /** Counts {@code count} sessions revoked for {@code reason}; zero counts nothing. */
    public void sessionsRevoked(AuthSession.RevokeReason reason, int count) {
        if (count > 0) {
            revocations.get(reason).increment(count);
        }
    }

    /** The tag value of SDD V19 for a revocation reason. */
    public static String reasonTag(AuthSession.RevokeReason reason) {
        return switch (reason) {
            case LOGOUT -> "logout";
            case TERMINATED -> "terminate";
            case REUSE -> "reuse";
            case PASSWORD_RESET -> "password_reset";
        };
    }
}
