package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Buckets stored in Redis through Bucket4j's compare-and-swap proxy on Lettuce, shared by every
 * instance (SDD V06, V16).
 *
 * <p>The connection is opened on first use, not at startup: the application must start and stay
 * ready while Redis is down (REQ-AVAIL-001), and step 3.5 adds local buckets for that case. Until
 * then a Redis failure propagates to the caller. Once open, Lettuce reconnects on its own.
 *
 * <p>Each key expires when its bucket would be full again, plus {@link #KEEP_AFTER_REFILL}, so
 * Redis only holds buckets that still limit something.
 */
final class RedisBucketSource implements BucketSource, AutoCloseable {

    /** Extra time a key lives after its bucket would be full again. */
    static final Duration KEEP_AFTER_REFILL = Duration.ofSeconds(10);

    private final RedisClient client;
    private final TimeMeter timeMeter;
    // ReentrantLock instead of synchronized: a virtual thread blocked in synchronized pins its carrier.
    private final ReentrantLock lock = new ReentrantLock();

    private StatefulRedisConnection<String, byte[]> connection;
    private volatile ProxyManager<String> proxyManager;

    RedisBucketSource(RedisClient client, TimeMeter timeMeter) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter");
    }

    /** A source for the Redis server at {@code host:port}; nothing connects until the first bucket. */
    static RedisBucketSource create(String host, int port, Duration commandTimeout, TimeMeter timeMeter) {
        RedisURI uri =
                RedisURI.Builder.redis(host, port).withTimeout(commandTimeout).build();
        return new RedisBucketSource(RedisClient.create(uri), timeMeter);
    }

    @Override
    public Bucket bucket(String key, RateLimitProperties.Limit limit) {
        Objects.requireNonNull(limit, "limit");
        return proxyManager().builder().build(key, () -> configuration(limit));
    }

    /** A bucket of {@code capacity} tokens refilled in full once per window (SDD V14-A2). */
    static BucketConfiguration configuration(RateLimitProperties.Limit limit) {
        return BucketConfiguration.builder()
                .addLimit(bandwidth ->
                        bandwidth.capacity(limit.capacity()).refillIntervally(limit.capacity(), limit.window()))
                .build();
    }

    @Override
    public void close() {
        lock.lock();
        try {
            proxyManager = null;
            if (connection != null) {
                connection.close();
                connection = null;
            }
            client.shutdown();
        } finally {
            lock.unlock();
        }
    }

    private ProxyManager<String> proxyManager() {
        ProxyManager<String> current = proxyManager;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (proxyManager == null) {
                connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
                proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                        .expirationAfterWrite(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(KEEP_AFTER_REFILL))
                        .clientClock(timeMeter)
                        .build();
            }
            return proxyManager;
        } finally {
            lock.unlock();
        }
    }
}
