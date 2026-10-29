package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.bucket4j.Bucket;
import java.util.function.Function;

/**
 * Where the token buckets of {@link BucketRateLimiter} are kept: Redis for every instance
 * ({@link RedisBucketSource}), instance-local memory ({@link LocalBucketSource}), or Redis with a
 * local fallback ({@link FailoverBucketSource}).
 */
interface BucketSource {

    /**
     * The bucket stored under {@code key}, created with {@code limit} when it does not exist yet.
     * The key never contains the raw subject.
     */
    Bucket bucket(String key, RateLimitProperties.Limit limit);

    /**
     * Runs {@code operation} on the bucket stored under {@code key}. A remote bucket fails when the
     * operation runs, not when it is obtained, so a source that falls back to another store
     * overrides this method; the default simply applies the operation.
     */
    default <T> T apply(String key, RateLimitProperties.Limit limit, Function<Bucket, T> operation) {
        return operation.apply(bucket(key, limit));
    }
}
