package io.github.kaulinmindiola.warden.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Answers 503 {@code service-unavailable} with {@code Retry-After: 5} when PostgreSQL is
 * unreachable and the failure escapes the servlet filters (REQ-REL-007, REQ-REL-009, ADR-007).
 *
 * <p>Errors inside Spring MVC are rendered by {@link ProblemDetailsAdvice}. This filter covers the
 * filter chain itself: with Redis and PostgreSQL both down, the access-token check falls back to
 * PostgreSQL inside Spring Security and fails there, before any controller runs. Other failures are
 * rethrown untouched. Runs after the request size limit and before the security chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DatabaseUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableFilter.class);

    private final HandlerExceptionResolver exceptionResolver;

    public DatabaseUnavailableFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException failure) {
            if (response.isCommitted() || !DatabaseUnavailable.causedBy(failure)) {
                throw failure;
            }
            log.warn(
                    "PostgreSQL is unavailable ({}); the request is denied with 503",
                    DatabaseUnavailable.describe(failure));
            exceptionResolver.resolveException(request, response, null, ProblemException.serviceUnavailable());
        }
    }
}
