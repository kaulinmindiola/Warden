package io.github.kaulinmindiola.warden.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bucket;
import io.github.kaulinmindiola.warden.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Semantics and memory bound of the instance-local buckets, on a test clock. */
class LocalBucketSourceTest {

    private static final RateLimitProperties.Limit LIMIT = new RateLimitProperties.Limit(3, Duration.ofMinutes(15));
    private static final RateLimitProperties.Limit SHORT = new RateLimitProperties.Limit(1, Duration.ofSeconds(30));

    private final MutableClock clock = new MutableClock();
    private final LocalBucketSource buckets = new LocalBucketSource(new ClockTimeMeter(clock));

    @Test
    void aBucketAllowsItsCapacityAndRefillsInFullOnlyAfterItsWindow() {
        Bucket bucket = buckets.bucket("a", LIMIT);
        for (int request = 1; request <= LIMIT.capacity(); request++) {
            assertThat(bucket.tryConsume(1)).as("request %d", request).isTrue();
        }
        assertThat(bucket.tryConsume(1)).isFalse();

        clock.advance(LIMIT.window().minusSeconds(1));
        assertThat(buckets.bucket("a", LIMIT).tryConsume(1)).isFalse();

        clock.advance(Duration.ofSeconds(2));
        assertThat(buckets.bucket("a", LIMIT).tryConsume(LIMIT.capacity())).isTrue();
    }

    @Test
    void eachKeyKeepsItsOwnBucket() {
        assertThat(buckets.bucket("a", LIMIT)).isSameAs(buckets.bucket("a", LIMIT));

        buckets.bucket("a", LIMIT).tryConsume(LIMIT.capacity());

        assertThat(buckets.bucket("b", LIMIT).tryConsume(1)).isTrue();
        assertThat(buckets.size()).isEqualTo(2);
    }

    @Test
    void theSweepDropsOnlyBucketsUnusedForAWholeWindow() {
        buckets.bucket("long", LIMIT).tryConsume(1);
        Bucket shortBucket = buckets.bucket("short", SHORT);
        shortBucket.tryConsume(1);

        clock.advance(LocalBucketSource.SWEEP_INTERVAL);
        buckets.bucket("other", LIMIT);

        assertThat(buckets.size()).as("long and other remain").isEqualTo(2);
        assertThat(buckets.bucket("short", SHORT)).isNotSameAs(shortBucket);
    }

    @Test
    void aBucketUsedWithinItsWindowSurvivesTheSweep() {
        Bucket shortBucket = buckets.bucket("short", SHORT);
        shortBucket.tryConsume(1);
        clock.advance(Duration.ofSeconds(40));
        buckets.bucket("short", SHORT);

        clock.advance(Duration.ofSeconds(20));
        buckets.bucket("other", LIMIT);

        assertThat(buckets.size()).isEqualTo(2);
        assertThat(buckets.bucket("short", SHORT)).isSameAs(shortBucket);
    }

    @Test
    void noSweepRunsBeforeItsInterval() {
        buckets.bucket("short", SHORT);

        clock.advance(LocalBucketSource.SWEEP_INTERVAL.minusSeconds(1));
        buckets.bucket("other", LIMIT);

        assertThat(buckets.size()).isEqualTo(2);
    }
}
