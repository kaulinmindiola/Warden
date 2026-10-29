package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.kaulinmindiola.warden.shared.web.ProblemException;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import java.time.Duration;
import java.util.Objects;

/**
 * Port for rate limiting (SDD V09). A subject is the value a policy limits: a normalized e-mail
 * address, a client IP or an id. Implementations never store the subject itself.
 *
 * <p>{@link #check} exists for limits that count failures only, such as failed logins per e-mail
 * (SDD V14-A2): the caller checks before doing the work and consumes only when the work fails, so
 * a blocked e-mail is rejected even with the right password.
 */
public interface RateLimiter {

    /** Whether one more request of {@code subject} fits {@code policy}, without consuming it. */
    Decision check(RateLimitPolicy policy, String subject);

    /** Consumes one request of {@code subject} from {@code policy} if it fits. */
    Decision consume(RateLimitPolicy policy, String subject);

    /**
     * Outcome of a rate-limit evaluation.
     *
     * @param allowed whether the request fits the policy
     * @param retryAfter zero when allowed; otherwise the positive time until the policy admits a
     *     request again, sent to the client as {@code Retry-After}
     */
    record Decision(boolean allowed, Duration retryAfter) {

        private static final Decision PERMIT = new Decision(true, Duration.ZERO);

        public Decision {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (allowed && !retryAfter.isZero()) {
                throw new IllegalArgumentException("An allowed decision has no retry-after");
            }
            if (!allowed && (retryAfter.isZero() || retryAfter.isNegative())) {
                throw new IllegalArgumentException("A rejected decision needs a positive retry-after");
            }
        }

        /** The request fits the policy. */
        public static Decision permit() {
            return PERMIT;
        }

        /** The request does not fit the policy; it may be retried after {@code retryAfter}. */
        public static Decision reject(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }

        /**
         * Does nothing when allowed; otherwise throws the {@code rate-limited} problem (429), which
         * is rendered with a {@code Retry-After} header.
         */
        public void requireAllowed() {
            if (!allowed) {
                throw ProblemException.withRetryAfter(ProblemType.RATE_LIMITED, retryAfter);
            }
        }
    }
}
