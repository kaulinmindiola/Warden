package io.github.kaulinmindiola.warden.shared.id;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the UUIDv7 generator, driven by the application clock. */
@Configuration(proxyBeanMethods = false)
public class IdConfiguration {

    @Bean
    public UuidV7Generator uuidV7Generator(Clock clock) {
        return new UuidV7Generator(clock);
    }
}
