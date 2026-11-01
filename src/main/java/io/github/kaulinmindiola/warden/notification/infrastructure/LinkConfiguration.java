package io.github.kaulinmindiola.warden.notification.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the {@link LinkBuilder} from {@code warden.notification.links}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LinkProperties.class)
class LinkConfiguration {

    @Bean
    LinkBuilder linkBuilder(LinkProperties properties) {
        return new LinkBuilder(properties);
    }
}
