package io.github.kaulinmindiola.warden.notification.infrastructure;

import io.github.kaulinmindiola.warden.notification.domain.EmailProviders;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Creates providers A and B and orders them by {@code warden.notification.providers.primary}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProviderProperties.class)
class EmailProviderConfiguration {

    @Bean
    HttpEmailProvider emailProviderA(EmailProviderProperties properties, JsonMapper jsonMapper) {
        return new HttpEmailProvider(
                "A", properties.aUrl(), properties.connectTimeout(), properties.requestTimeout(), jsonMapper);
    }

    @Bean
    HttpEmailProvider emailProviderB(EmailProviderProperties properties, JsonMapper jsonMapper) {
        return new HttpEmailProvider(
                "B", properties.bUrl(), properties.connectTimeout(), properties.requestTimeout(), jsonMapper);
    }

    @Bean
    EmailProviders emailProviders(
            EmailProviderProperties properties, HttpEmailProvider emailProviderA, HttpEmailProvider emailProviderB) {
        return "a".equals(properties.primary())
                ? new EmailProviders(emailProviderA, emailProviderB)
                : new EmailProviders(emailProviderB, emailProviderA);
    }
}
