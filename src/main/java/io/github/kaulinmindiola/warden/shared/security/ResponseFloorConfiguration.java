package io.github.kaulinmindiola.warden.shared.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the {@link ResponseFloor} used by registration and password-reset requests (ADR-019). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResponseFloorProperties.class)
public class ResponseFloorConfiguration {

    @Bean
    public ResponseFloor responseFloor(ResponseFloorProperties properties) {
        return new ResponseFloor(properties.responseFloor());
    }
}
