package io.github.kaulinmindiola.warden.shared.ratelimit;

import java.time.Duration;

/** Rate-limit settings for unit tests. */
final class RateLimitFixtures {

    private RateLimitFixtures() {}

    /** The same limit for every policy and the default Redis command timeout. */
    static RateLimitProperties uniform(int capacity, Duration window) {
        RateLimitProperties.Limit limit = new RateLimitProperties.Limit(capacity, window);
        return new RateLimitProperties(limit, limit, limit, limit, limit, limit, limit, limit, Duration.ofMillis(200));
    }
}
