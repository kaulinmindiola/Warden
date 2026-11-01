package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.BackoffPolicy;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@code warden.notification.delivery} and creates the backoff policy of the worker. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryProperties.class)
class DeliveryConfiguration {

    @Bean
    BackoffPolicy backoffPolicy(DeliveryProperties properties) {
        DeliveryProperties.Backoff backoff = properties.backoff();
        return new BackoffPolicy(
                backoff.initial(),
                backoff.multiplier(),
                backoff.max(),
                backoff.jitter(),
                () -> ThreadLocalRandom.current().nextDouble());
    }
}
