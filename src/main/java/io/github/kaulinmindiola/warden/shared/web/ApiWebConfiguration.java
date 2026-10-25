package io.github.kaulinmindiola.warden.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves every Warden REST controller under the version prefix (REQ-INT-001). Controllers declare
 * paths without the prefix; actuator and springdoc endpoints are not affected.
 */
@Configuration(proxyBeanMethods = false)
public class ApiWebConfiguration implements WebMvcConfigurer {

    /** Version prefix of the public API. */
    public static final String API_PREFIX = "/api/v1";

    private static final String BASE_PACKAGE = "io.github.kaulinmindiola.warden";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                API_PREFIX,
                HandlerTypePredicate.forBasePackage(BASE_PACKAGE)
                        .and(HandlerTypePredicate.forAnnotation(RestController.class)));
    }
}
