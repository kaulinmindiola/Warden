package io.github.kaulinmindiola.warden.shared.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Minimum response time of the enumeration-sensitive endpoints (SDD V14-A3, ADR-019).
 *
 * @param responseFloor {@code warden.security.response-floor}; 250 ms until the REQ-SEC-010
 *     analysis calibrates it above the slow path's p99
 */
@ConfigurationProperties("warden.security")
public record ResponseFloorProperties(@DefaultValue("250ms") Duration responseFloor) {

    public ResponseFloorProperties {
        if (responseFloor == null || responseFloor.isNegative()) {
            throw new IllegalArgumentException("warden.security.response-floor must be zero or positive");
        }
    }
}
