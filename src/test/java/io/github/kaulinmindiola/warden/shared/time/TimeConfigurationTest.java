package io.github.kaulinmindiola.warden.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TimeConfigurationTest {

    @Test
    @Verifies("REQ-INT-007")
    void theApplicationClockIsUtc() {
        assertThat(new TimeConfiguration().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
