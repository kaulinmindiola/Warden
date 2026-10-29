package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.bucket4j.Bucket;

/**
 * Where the token buckets of {@link BucketRateLimiter} are kept: Redis for every instance
 * ({@link RedisBucketSource}) and, from step 3.5, instance-local buckets while Redis is down.
 */
interface BucketSource {

    /**
     * The bucket stored under {@code key}, created with {@code limit} when it does not exist yet.
     * The key never contains the raw subject.
     */
    Bucket bucket(String key, RateLimitProperties.Limit limit);
}
