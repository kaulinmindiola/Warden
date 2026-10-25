package io.github.kaulinmindiola.warden.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless, deny-by-default security (REQ-SEC-001, SDD V11, V18).
 *
 * <p>Every request requires authentication except the public paths below. There is no session,
 * no CSRF protection (no cookies or ambient credentials are accepted), no form login and no HTTP
 * Basic. Authentication failures and access denials are rendered as problem details.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * Paths reachable without authentication. The actuator paths exist only on the management
     * port, which is never published outside the deployment network.
     */
    static final String[] PUBLIC_PATHS = {"/actuator/health", "/actuator/health/**", "/actuator/prometheus"};

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler)
            throws Exception {
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
