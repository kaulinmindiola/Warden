package io.github.kaulinmindiola.warden.shared.ratelimit;

import io.github.kaulinmindiola.warden.shared.crypto.KeyedHasher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link RateLimiter}, backed by buckets in the Redis server of {@code
 * spring.data.redis.host} and {@code spring.data.redis.port}.
 *
 * <p>The rate limiter uses its own Lettuce connection, with the short command timeout of {@code
 * warden.ratelimit.redis-command-timeout}, instead of the connection of Spring Data Redis.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean(destroyMethod = "close")
    RedisBucketSource redisBucketSource(
            RateLimitProperties properties,
            Clock clock,
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        return RedisBucketSource.create(host, port, properties.redisCommandTimeout(), new ClockTimeMeter(clock));
    }

    @Bean
    RateLimiter rateLimiter(
            RedisBucketSource redisBucketSource,
            RateLimitProperties properties,
            KeyedHasher keyedHasher,
            MeterRegistry meterRegistry) {
        return new BucketRateLimiter(redisBucketSource, properties, keyedHasher, meterRegistry);
    }
}
