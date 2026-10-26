package io.github.kaulinmindiola.warden.shared.security;

import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless, deny-by-default security (REQ-SEC-001, SDD V11, V18).
 *
 * <p>Every request requires a valid access token except the public paths below. The token is
 * read only from the {@code Authorization: Bearer} header (REQ-INT-002) and validated by the
 * {@link JwtDecoder} that the {@code iam} module provides. Public API endpoints ignore that header,
 * so a stale access token never blocks login, refresh or password recovery, and the refresh
 * endpoint can reject a refresh token sent there. There is no session, no CSRF protection (no
 * cookies or ambient credentials are accepted), no form login and no HTTP Basic. Authentication
 * failures and access denials are rendered as problem details.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** Public endpoints of REQ-SEC-001. They match exactly, so sub-paths stay protected. */
    static final Set<String> PUBLIC_API_PATHS = Set.of(
            ApiWebConfiguration.API_PREFIX + "/auth/register",
            ApiWebConfiguration.API_PREFIX + "/auth/email/verify",
            ApiWebConfiguration.API_PREFIX + "/auth/login",
            ApiWebConfiguration.API_PREFIX + "/auth/refresh",
            ApiWebConfiguration.API_PREFIX + "/auth/password/forgot",
            ApiWebConfiguration.API_PREFIX + "/auth/password/reset");

    /**
     * Paths reachable without authentication: the public API endpoints and the actuator paths,
     * which exist only on the management port, never published outside the deployment network.
     */
    static final String[] PUBLIC_PATHS = Stream.concat(
                    Stream.of("/actuator/health", "/actuator/health/**", "/actuator/prometheus"),
                    PUBLIC_API_PATHS.stream())
            .toArray(String[]::new);

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder accessTokenDecoder,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        JwtAuthenticationProvider jwtProvider = new JwtAuthenticationProvider(accessTokenDecoder);
        jwtProvider.setJwtAuthenticationConverter(new CurrentUserAuthenticationConverter());

        DefaultBearerTokenResolver headerOnly = new DefaultBearerTokenResolver();
        headerOnly.setAllowUriQueryParameter(false);
        headerOnly.setAllowFormEncodedBodyParameter(false);
        BearerTokenResolver ignoredOnPublicEndpoints =
                request -> PUBLIC_API_PATHS.contains(request.getRequestURI()) ? null : headerOnly.resolve(request);

        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(ignoredOnPublicEndpoints)
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.authenticationManager(new ProviderManager(jwtProvider))))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Warden never authenticates usernames and passwords through Spring Security: login is an
     * application use case. Declaring this manager stops Spring Boot from creating an in-memory
     * user whose generated password would be written to the log.
     */
    @Bean
    AuthenticationManager noPasswordAuthenticationManager() {
        return authentication -> {
            throw new ProviderNotFoundException("Password authentication is not handled by Spring Security");
        };
    }
}
