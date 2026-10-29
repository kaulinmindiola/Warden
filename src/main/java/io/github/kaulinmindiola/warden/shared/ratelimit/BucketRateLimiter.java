package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import io.github.kaulinmindiola.warden.shared.crypto.KeyedHasher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@link RateLimiter} on Bucket4j token buckets. The bucket of a request is identified by its
 * policy and the HMAC of its subject, so no e-mail address, IP or id reaches the store.
 *
 * <p>Every rejection, from {@code check} or {@code consume}, increments {@code
 * warden.ratelimit.rejections} tagged with the policy key (Prometheus: {@code
 * warden_ratelimit_rejections_total}). The counters are registered at startup, so every policy
 * appears with zero before its first rejection.
 */
final class BucketRateLimiter implements RateLimiter {

    static final String KEY_PREFIX = "warden:rl:";
    static final String REJECTIONS_METRIC = "warden.ratelimit.rejections";
    static final String POLICY_TAG = "policy";

    private final BucketSource buckets;
    private final RateLimitProperties properties;
    private final KeyedHasher keyedHasher;
    private final Map<RateLimitPolicy, Counter> rejections = new EnumMap<>(RateLimitPolicy.class);

    BucketRateLimiter(
            BucketSource buckets,
            RateLimitProperties properties,
            KeyedHasher keyedHasher,
            MeterRegistry meterRegistry) {
        this.buckets = Objects.requireNonNull(buckets, "buckets");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.keyedHasher = Objects.requireNonNull(keyedHasher, "keyedHasher");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            rejections.put(
                    policy,
                    Counter.builder(REJECTIONS_METRIC)
                            .description("Requests rejected by a rate limit")
                            .tag(POLICY_TAG, policy.key())
                            .register(meterRegistry));
        }
    }

    @Override
    public Decision check(RateLimitPolicy policy, String subject) {
        EstimationProbe probe = bucketOf(policy, subject).estimateAbilityToConsume(1);
        return probe.canBeConsumed() ? Decision.permit() : reject(policy, probe.getNanosToWaitForRefill());
    }

    @Override
    public Decision consume(RateLimitPolicy policy, String subject) {
        ConsumptionProbe probe = bucketOf(policy, subject).tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Decision.permit() : reject(policy, probe.getNanosToWaitForRefill());
    }

    /** Store key of a bucket: {@code warden:rl:{policy}:{hmac(subject)}} (SDD V06). */
    static String key(RateLimitPolicy policy, String hashedSubject) {
        return KEY_PREFIX + policy.key() + ":" + hashedSubject;
    }

    private Bucket bucketOf(RateLimitPolicy policy, String subject) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("A rate limit subject must not be blank");
        }
        return buckets.bucket(key(policy, keyedHasher.hmac(subject)), properties.limitFor(policy));
    }

    private Decision reject(RateLimitPolicy policy, long nanosToWait) {
        rejections.get(policy).increment();
        // A rejected probe always reports a wait; the floor keeps the decision valid if it does not.
        return Decision.reject(Duration.ofNanos(Math.max(1L, nanosToWait)));
    }
}
